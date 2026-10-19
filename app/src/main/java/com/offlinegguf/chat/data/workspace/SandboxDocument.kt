package com.offlinegguf.chat.data.workspace

/**
 * Turns a code snippet into a self-contained HTML document for the sandbox.
 *
 * Pure string work — no Android — so the shell structure is unit-tested on the
 * JVM. The rules:
 *
 * - `html` that already is a document is used as-is (minus a console hook);
 *   fragments get a viewport-sized shell around them.
 * - `css` becomes a live stylesheet over a small demo page so the styles show.
 * - `js` runs inside a shell that mirrors `console.*` into the page, so print
 *   debugging works without any device log access.
 * - `svg` renders directly.
 *
 * User content is substituted *after* template trimming so a snippet can never
 * be mangled by margin processing. The sandbox WebView blocks every network
 * request separately; nothing built here can phone home either.
 */
object SandboxDocument {

    private val WEB_LANGUAGES = setOf("html", "htm", "css", "js", "javascript", "svg")

    fun isWebLanguage(language: String): Boolean = language.trim().lowercase() in WEB_LANGUAGES

    /** File name to suggest when saving into a workspace. */
    fun fileNameFor(language: String, explicit: String?): String {
        val safe = explicit?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()
        if (!safe.isNullOrBlank()) return safe
        return when (language.trim().lowercase()) {
            "html", "htm" -> "index.html"
            "css" -> "styles.css"
            "js", "javascript" -> "script.js"
            "svg" -> "image.svg"
            else -> "snippet.txt"
        }
    }

    /** A full HTML document for [content], ready for `loadDataWithBaseURL`. */
    fun assemble(language: String, content: String): String = when (language.trim().lowercase()) {
        "css" -> shell(body = DEMO_BODY, head = "<style>\n$content\n</style>", script = "")
        "js", "javascript" -> shell(body = "", head = "", script = content)
        "svg" -> shell(body = content, head = "", script = "")
        else -> htmlDocument(content)
    }

    private fun htmlDocument(content: String): String {
        val looksWhole = content.contains("<html", ignoreCase = true) ||
            content.contains("<!doctype", ignoreCase = true)
        return if (looksWhole) {
            withConsoleHook(content)
        } else {
            shell(body = content, head = "", script = "")
        }
    }

    /** The wrapper: viewport, ink-on-paper defaults, console capture. */
    private fun shell(body: String, head: String, script: String): String {
        val template = """
            |<!doctype html>
            |<html>
            |<head>
            |<meta charset="utf-8">
            |<meta name="viewport" content="width=device-width, initial-scale=1">
            |<style>
            |  html { color-scheme: light dark; }
            |  body { font: 15px/1.5 system-ui, sans-serif; margin: 12px; }
            |  pre#sandbox-console {
            |    white-space: pre-wrap; word-break: break-word;
            |    font: 12px/1.45 ui-monospace, monospace;
            |    border-top: 1px solid rgba(128,128,128,.45); margin-top: 18px; padding-top: 8px;
            |    opacity: .85;
            |  }
            |</style>
            |%%HEAD%%
            |</head>
            |<body>
            |%%BODY%%
            |<pre id="sandbox-console"></pre>
            |%%CONSOLE%%
            |<script>
            |%%SCRIPT%%
            |</script>
            |</body>
            |</html>
        """.trimMargin()
        return template
            .replace("%%HEAD%%", head)
            .replace("%%BODY%%", body)
            .replace("%%CONSOLE%%", consoleHook())
            .replace("%%SCRIPT%%", script)
    }

    /**
     * Inject the console hook into a document the model produced. Best effort:
     * appends before `</body>` when there is one, otherwise after the document
     * text (browsers still run it).
     */
    private fun withConsoleHook(document: String): String {
        val hook = "<pre id=\"sandbox-console\"></pre>\n" + consoleHook()
        val closing = Regex("</body>", RegexOption.IGNORE_CASE)
        return if (closing.containsMatchIn(document)) {
            document.replaceFirst(closing, hook + "</body>")
        } else {
            document + hook
        }
    }

    /** Mirrors console output into #sandbox-console and traps page errors. */
    private fun consoleHook(): String {
        val template = """
            |<script>
            |(function () {
            |  var out = document.getElementById('sandbox-console');
            |  if (!out) return;
            |  function write(kind, args) {
            |    try {
            |      var line = Array.prototype.map.call(args, function (a) {
            |        if (typeof a === 'object') { try { return JSON.stringify(a); } catch (e) { return String(a); } }
            |        return String(a);
            |      }).join(' ');
            |      out.textContent += (kind ? kind + ': ' : '') + line + '\n';
            |    } catch (e) {}
            |  }
            |  ['log', 'info', 'warn', 'error', 'debug'].forEach(function (m) {
            |    var original = console[m];
            |    console[m] = function () {
            |      write(m === 'log' ? '' : m, arguments);
            |      if (original) original.apply(console, arguments);
            |    };
            |  });
            |  window.addEventListener('error', function (e) {
            |    write('error', [e.message + ' (line ' + e.lineno + ')']);
            |  });
            |})();
            |</script>
        """.trimMargin()
        // Inside a Kotlin raw string `'\n'` stays backslash-n, which is exactly
        // the JS newline escape we want in the emitted script.
        return template
    }

    /** Demo markup for pure-CSS snippets so the styles have something to bite. */
    private val DEMO_BODY = """
        <h1>CSS sandbox</h1>
        <p>Your stylesheet is live. This page is the specimen it styles.</p>
        <button>Button</button> <a href="#">link</a>
        <ul><li>list item</li><li>another</li></ul>
        <pre>preformatted</pre>
        <table border="1" cellpadding="6"><tr><th>head</th><th>head</th></tr><tr><td>cell</td><td>cell</td></tr></table>
    """.trimIndent()
}
