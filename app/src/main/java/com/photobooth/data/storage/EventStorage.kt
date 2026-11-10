package com.photobooth.data.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.db.entity.EventEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-disk layout of an event: `<root>/Photobooth/<EventId>_<Name>/{Originals,Composited_Prints,Frames,Sessions}`.
 * A guest session lives in `Sessions/<SessionId>/{Originals/Photo_N.jpg, Montage_Final.jpg}`; it counts as validated once the montage exists.
 * Frames live inside the event folder so they are never shared between events.
 */
@Singleton
class EventStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Creates the event folder, preferring public Pictures and falling back to app-specific storage. */
    fun createEventDirectory(eventId: String, eventName: String): File {
        val folderName = "${eventId}_${sanitize(eventName)}"
        val roots = listOfNotNull(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ROOT_NAME),
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, ROOT_NAME) },
            File(context.filesDir, ROOT_NAME),
        )
        for (root in roots) {
            val dir = uniqueDirectory(root, folderName)
            if (ensureStructure(dir)) {
                Log.i(TAG, "EventStorage: created ${dir.absolutePath}")
                return dir
            }
            Log.w(TAG, "EventStorage: cannot use ${root.absolutePath}, trying next location")
        }
        throw IOException("No writable storage location for event folders")
    }

    /** Idempotently creates the standard subfolders; returns false when the folder is not writable. */
    fun ensureStructure(dir: File): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return SUBFOLDERS.all { sub -> File(dir, sub).let { it.isDirectory || it.mkdirs() } } && dir.canWrite()
    }

    fun directoryOf(event: EventEntity): File = File(requireNotNull(Uri.parse(event.storageDirectoryUri).path))

    fun subfolder(event: EventEntity, name: String): File =
        File(directoryOf(event), name).also { it.mkdirs() }

    fun sessionDirectory(event: EventEntity, sessionId: String): File = File(subfolder(event, SESSIONS), sessionId)

    fun sessionOriginals(event: EventEntity, sessionId: String): File =
        File(sessionDirectory(event, sessionId), ORIGINALS).also { it.mkdirs() }

    /** Deletes session folders that never got their final montage (aborted, or the app died mid-session). */
    fun purgeIncompleteSessions(event: EventEntity): Int {
        val sessions = File(directoryOf(event), SESSIONS).listFiles { f -> f.isDirectory }.orEmpty()
        return sessions.count { dir -> !File(dir, MONTAGE_FILE).isFile && dir.deleteRecursively() }
    }

    /** Guest photos only (validated sessions plus legacy flat originals); diagnostic TEST_ files do not count. */
    fun countPhotos(event: EventEntity): Int {
        fun jpegs(dir: File) = dir.listFiles { f ->
            f.isFile && !f.name.startsWith(TEST_PREFIX) && f.extension.lowercase() in PHOTO_EXTENSIONS
        }?.size ?: 0
        val sessions = File(directoryOf(event), SESSIONS).listFiles { f -> f.isDirectory && File(f, MONTAGE_FILE).isFile }.orEmpty()
        return jpegs(File(directoryOf(event), ORIGINALS)) + sessions.sumOf { jpegs(File(it, ORIGINALS)) }
    }

    private fun uniqueDirectory(root: File, name: String): File {
        var candidate = File(root, name)
        var n = 2
        while (candidate.exists()) candidate = File(root, "${name}_${n++}")
        return candidate
    }

    private fun sanitize(name: String): String =
        name.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('.', '_')
            .take(60)
            .ifEmpty { "Event" }

    companion object {
        const val ROOT_NAME = "Photobooth"
        const val ORIGINALS = "Originals"
        const val COMPOSITED_PRINTS = "Composited_Prints"
        const val FRAMES = "Frames"
        const val SESSIONS = "Sessions"
        const val MONTAGE_FILE = "Montage_Final.jpg"
        const val TEST_PREFIX = "TEST_"
        val SUBFOLDERS = listOf(ORIGINALS, COMPOSITED_PRINTS, FRAMES, SESSIONS)
        private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg")
    }
}
