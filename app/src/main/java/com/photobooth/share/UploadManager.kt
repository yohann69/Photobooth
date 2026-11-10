package com.photobooth.share

import android.util.Log
import com.photobooth.core.EventDates
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.db.entity.EventEntity
import com.photobooth.data.settings.SettingsRepository
import com.photobooth.di.ApplicationScope
import com.photobooth.domain.model.LayoutType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What every upload of one session carries besides the image itself. */
class UploadContext(val sessionId: String, val eventName: String, val eventDate: String, val layoutType: LayoutType) {
    companion object {
        fun of(sessionId: String, event: EventEntity, layoutType: LayoutType) = UploadContext(
            sessionId, event.name, EventDates.format(event.eventDate.takeIf { it > 0 } ?: event.createdAt, "yyyy-MM-dd", Locale.US), layoutType,
        )
    }
}

/**
 * Keeps the event's Drive folder in step with the booth, through the Transat backend:
 * - each validated photo goes up [SHOT_DELAY_MS] later, in the background;
 * - the collage goes up when the guest prints, then the backend issues the permanent share link for the QR code
 *   once every picture is confirmed on Drive;
 * - an abandoned session is removed from Drive entirely, including uploads still in flight.
 * Pending work lives in memory only; the backend's trash job catches whatever the booth could not finish.
 */
@Singleton
class UploadManager @Inject constructor(
    private val api: TransatShareApi,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private class Shot(val number: Int) {
        var job: Job? = null
        @Volatile var done = false
    }

    private class Session {
        val shots = HashMap<Int, Shot>()
        @Volatile var cancelled = false
    }

    private val lock = Any()
    private val sessions = HashMap<String, Session>()
    /** One upload at a time: the event hotspot is the bottleneck, and order keeps the logs readable. */
    private val uploadSlot = Mutex()

    private suspend fun server(): TransatServer? {
        val s = settings.settings.first()
        if (!s.uploadConfigured) return null
        val credential = BoothCredential.parse(s.uploadToken) ?: return null.also { Log.w(TAG, "UploadManager: booth key is malformed") }
        return TransatServer(s.uploadBaseUrl, credential)
    }

    /** True when this event shares online and the server is set up. */
    suspend fun isEnabledFor(event: EventEntity): Boolean = event.enableDigitalQRShare && server() != null

    /** Checks the key and the server from the admin panel. */
    suspend fun testConnection(baseUrl: String, key: String): Result<Unit> = withContext(Dispatchers.IO) {
        val credential = BoothCredential.parse(key) ?: return@withContext Result.failure(IOException("Key must look like booth-1.<secret>"))
        if (com.photobooth.BuildConfig.DEBUG) Log.d(TAG, "UploadManager: testing key ${credential.keyId} fingerprint ${credential.fingerprint()}")
        runCatching { TransatServer(baseUrl, credential) }.mapCatching { api.ping(it).getOrThrow() }
    }

    /** The guest validated photo [number]: upload it shortly, in the background. */
    fun scheduleShot(ctx: UploadContext, number: Int, file: File) {
        val shot = Shot(number)
        val previous = synchronized(lock) {
            val session = sessions.getOrPut(ctx.sessionId) { Session() }
            if (session.cancelled) return
            session.shots.put(number, shot)
        }
        previous?.job?.cancel()
        shot.job = scope.launch {
            delay(SHOT_DELAY_MS)
            uploadUntilDone(ctx, UploadKind.PHOTO, number, file)
            shot.done = true
        }
    }

    /**
     * Uploads the collage, makes sure every validated photo is up, and returns the share link for the QR code.
     * Photos whose background upload failed are retried here.
     */
    suspend fun publish(ctx: UploadContext, collage: File, photoCount: Int, photos: List<File>): Result<String> = withContext(Dispatchers.IO) {
        val server = server() ?: return@withContext Result.failure(IOException("Serveur d'envoi non configuré"))
        synchronized(lock) { sessions.getOrPut(ctx.sessionId) { Session() } }
        if (!uploadUntilDone(ctx, UploadKind.COLLAGE, 0, collage)) {
            return@withContext Result.failure(IOException("Envoi du collage impossible"))
        }
        val shots = synchronized(lock) { sessions[ctx.sessionId]?.shots?.values?.toList().orEmpty() }
        shots.forEach { it.job?.join() }
        for ((index, file) in photos.withIndex()) {
            val number = index + 1
            if (shots.none { it.number == number && it.done } && !uploadUntilDone(ctx, UploadKind.PHOTO, number, file)) {
                return@withContext Result.failure(IOException("Envoi de la photo $number impossible"))
            }
        }
        retrying("finalize ${ctx.sessionId}") { api.finalize(server, ctx.sessionId, photoCount) }
            ?.let { Result.success(it) }
            ?: Result.failure(IOException("Le serveur n'a pas renvoyé de lien"))
    }

    /** The session was abandoned: nothing of it may stay online. */
    fun deleteSession(sessionId: String) {
        val session = synchronized(lock) { sessions.remove(sessionId)?.also { it.cancelled = true } } ?: return
        scope.launch {
            // Uploads already talking to the server finish first, so the cancel below also covers them.
            session.shots.values.forEach { it.job?.cancelAndJoin() }
            withContext(NonCancellable + Dispatchers.IO) {
                val server = server() ?: return@withContext
                val ok = retrying("cancel $sessionId") { api.cancel(server, sessionId) } != null
                Log.i(TAG, "UploadManager: session $sessionId removed from Drive=$ok")
            }
        }
    }

    /** The session was shared: only the bookkeeping goes. */
    fun complete(sessionId: String) {
        synchronized(lock) { sessions.remove(sessionId) }
    }

    /** Start, send to Drive, confirm; the whole sequence again on failure. False once retries are exhausted. */
    private suspend fun uploadUntilDone(ctx: UploadContext, kind: UploadKind, number: Int, file: File): Boolean {
        val server = server() ?: return false
        if (!file.isFile) return false
        val request = UploadRequest(file, kind, number, ctx.eventName, ctx.eventDate, ctx.layoutType.name)
        // Once started, an upload is not interrupted: a cancel waits for it and then removes it on the server.
        return withContext(NonCancellable + Dispatchers.IO) {
            uploadSlot.withLock {
                if (isCancelled(ctx.sessionId)) return@withLock false
                val md5 = TransatShareApi.md5Hex(file)
                val started = System.currentTimeMillis()
                val ok = retrying("$kind $number of ${ctx.sessionId}") {
                    api.start(server, ctx.sessionId, request, md5).mapCatching { up ->
                        val driveId = api.sendToDrive(up.uploadUrl, file).getOrThrow()
                        api.complete(server, ctx.sessionId, up.fileId, driveId).getOrElse { e ->
                            // 400: the bytes reached Drive damaged (the server trashed them): send them again.
                            throw if (e is ApiException && e.code == 400) IOException(e.message) else e
                        }
                        up.fileName
                    }
                }
                if (ok != null) {
                    Log.i(TAG, "UploadManager: $kind $number of ${ctx.sessionId} on Drive as $ok " +
                        "(${file.length() / 1024} kB in ${System.currentTimeMillis() - started} ms)")
                }
                ok != null
            }
        }
    }

    private fun isCancelled(sessionId: String): Boolean = synchronized(lock) {
        val session = sessions[sessionId]
        // A session no longer tracked was abandoned (or already shared).
        session == null || session.cancelled
    }

    private suspend fun <T> retrying(what: String, call: () -> Result<T>): T? {
        for ((attempt, wait) in (listOf(0L) + RETRY_DELAYS_MS).withIndex()) {
            if (wait > 0) delay(wait)
            val result = call()
            result.getOrNull()?.let { return it }
            val error = result.exceptionOrNull()
            Log.w(TAG, "UploadManager: $what failed (attempt ${attempt + 1}): ${error?.message}")
            if (error is ApiException && !error.retryable) return null
            if (error is IllegalArgumentException) return null
        }
        return null
    }

    private companion object {
        const val SHOT_DELAY_MS = 1_500L
        val RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L, 20_000L)
    }
}
