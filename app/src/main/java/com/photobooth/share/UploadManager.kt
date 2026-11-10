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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * Keeps the online gallery in step with the booth: each shot goes up [SHOT_DELAY_MS] after it is taken, a shot that
 * is retaken or a session that is abandoned is removed from the server, and the final montage returns the link
 * shown in the QR code. Pending work lives in memory only.
 */
@Singleton
class UploadManager @Inject constructor(
    private val api: RemoteGalleryApi,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private class Shot {
        var job: Job? = null
        var remoteId: String? = null
        var cancelled = false
    }

    private val lock = Any()
    private val shots = HashMap<String, MutableMap<Int, Shot>>()
    private val montages = HashMap<String, String>()

    private class Server(val baseUrl: String, val token: String)

    private suspend fun server(): Server? =
        settings.settings.first().takeIf { it.uploadConfigured }?.let { Server(it.uploadBaseUrl, it.uploadToken) }

    /** True when this event shares online and a server is set up. */
    suspend fun isEnabledFor(event: EventEntity): Boolean = event.enableDigitalQRShare && server() != null

    fun scheduleShot(ctx: UploadContext, number: Int, file: File) {
        val shot = Shot()
        shot.job = scope.launch {
            delay(SHOT_DELAY_MS)
            val server = server() ?: return@launch
            if (!file.isFile) return@launch
            // Past this point the request cannot be interrupted, so a late cancel is settled by deleting afterwards.
            val id = withContext(NonCancellable + Dispatchers.IO) {
                retrying("photo $number of ${ctx.sessionId}") {
                    api.upload(server.baseUrl, server.token, UploadRequest(file, UploadKind.PHOTO, ctx.sessionId, ctx.layoutType.name, ctx.eventName, ctx.eventDate, number))
                }
            }?.remoteId ?: return@launch
            val deleteNow = synchronized(lock) {
                shot.remoteId = id
                shot.cancelled
            }
            if (deleteNow) withContext(NonCancellable) { remove(server, id) }
        }
        synchronized(lock) { shots.getOrPut(ctx.sessionId) { HashMap() }[number] = shot }
    }

    /** The guest retook the photo: stop its upload or remove it from the server. */
    fun cancelShot(sessionId: String, number: Int) {
        val shot = synchronized(lock) { shots[sessionId]?.remove(number) } ?: return
        discard(shot)
    }

    /** The session was abandoned: nothing of it may stay online. */
    fun deleteSession(sessionId: String) {
        val (pending, montage) = synchronized(lock) { shots.remove(sessionId).orEmpty().values.toList() to montages.remove(sessionId) }
        pending.forEach(::discard)
        if (montage != null) scope.launch { server()?.let { remove(it, montage) } }
    }

    /** The session was validated: its uploads stay, only the bookkeeping goes. */
    fun complete(sessionId: String) {
        synchronized(lock) { shots.remove(sessionId); montages.remove(sessionId) }
    }

    /** Uploads the final collage and returns the download link for the session. */
    suspend fun uploadMontage(ctx: UploadContext, file: File): Result<String> = withContext(Dispatchers.IO) {
        val server = server() ?: return@withContext Result.failure(IOException("Serveur d'envoi non configuré"))
        val result = retrying("montage of ${ctx.sessionId}") {
            api.upload(server.baseUrl, server.token, UploadRequest(file, UploadKind.MONTAGE, ctx.sessionId, ctx.layoutType.name, ctx.eventName, ctx.eventDate, 0))
        }
        if (result == null) return@withContext Result.failure(IOException("Envoi du collage impossible"))
        synchronized(lock) { montages[ctx.sessionId] = result.remoteId }
        result.downloadUrl?.let { Result.success(it) } ?: Result.failure(IOException("Le serveur n'a pas renvoyé de lien"))
    }

    private fun discard(shot: Shot) {
        val id = synchronized(lock) {
            shot.cancelled = true
            shot.remoteId
        }
        shot.job?.cancel()
        if (id != null) scope.launch { server()?.let { remove(it, id) } }
    }

    private suspend fun remove(server: Server, remoteId: String) {
        retrying("delete $remoteId") { api.delete(server.baseUrl, server.token, remoteId) }
    }

    private suspend fun <T> retrying(what: String, call: () -> Result<T>): T? {
        for ((attempt, wait) in (listOf(0L) + RETRY_DELAYS_MS).withIndex()) {
            if (wait > 0) delay(wait)
            val result = call()
            result.getOrNull()?.let { return it }
            Log.w(TAG, "UploadManager: $what failed (attempt ${attempt + 1}): ${result.exceptionOrNull()?.message}")
        }
        return null
    }

    private companion object {
        const val SHOT_DELAY_MS = 2_500L
        val RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L)
    }
}
