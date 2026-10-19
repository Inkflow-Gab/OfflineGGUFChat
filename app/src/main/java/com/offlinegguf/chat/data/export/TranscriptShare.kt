package com.offlinegguf.chat.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Turns a transcript into something the system share sheet can hand to another app.
 *
 * The text goes out as a real file instead of as [Intent.EXTRA_TEXT]. A long
 * conversation as a string extra is unreliable — receivers truncate or drop it,
 * and nothing lands on disk for the user to keep — while a `.md` or `.json` file
 * is what people actually want to archive or open elsewhere.
 *
 * The file is written to the cache directory and shared through the FileProvider
 * declared in the manifest, whose `cache-path` entry covers exactly that
 * directory, with a read grant for whoever receives the URI. The cache is the
 * right place: the system can reclaim it, and the receiver copies out whatever it
 * needs.
 *
 * Call [buildTranscriptShare] from a background thread — it writes a file.
 *
 * @return a chooser intent, or null if the file could not be written or the
 *   provider has no root matching the cache directory (a `file_paths.xml`
 *   mistake), so the caller can say so instead of failing silently.
 */
fun buildTranscriptShare(
    context: Context,
    text: String,
    title: String,
    extension: String,
    mime: String,
): Intent? {
    val uri = runCatching {
        val file = File(context.cacheDir, transcriptFileName(title, extension))
        file.writeText(text)
        // packageName carries the debug applicationIdSuffix, which is what the
        // manifest placeholder for the provider authority resolves to.
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return null

    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "Export")
}

/**
 * Starts the share sheet. Must be called on the main thread.
 *
 * @return false when nothing on the device can take the share — a stock phone can
 *   genuinely have no app registered for `text/markdown` — so the caller can tell
 *   the user rather than leaving a menu item that appears to do nothing.
 */
fun launchShare(context: Context, intent: Intent?): Boolean {
    if (intent == null) return false
    return runCatching { context.startActivity(intent) }.isSuccess
}

/**
 * A conversation title is user text; a file name cannot be.
 *
 * Everything outside letters, digits, `+` and spaces becomes a separator, then
 * runs of separators and whitespace collapse to a single hyphen, so `../../etc/passwd`
 * yields `etc-passwd.md` rather than something that escapes the cache directory or
 * needs quoting in a shell. The stem is capped so a long title cannot push the name
 * past the limit most file systems impose, and a title made only of punctuation
 * still produces a usable name.
 */
internal fun transcriptFileName(title: String, extension: String): String {
    val stem = title
        .replace(Regex("[^A-Za-z0-9+ ]"), "_")
        .replace(Regex("[_.\\-\\s]+"), "-")
        .take(80)
        .trim('-')
        .ifBlank { "transcript" }
    return "$stem.$extension"
}
