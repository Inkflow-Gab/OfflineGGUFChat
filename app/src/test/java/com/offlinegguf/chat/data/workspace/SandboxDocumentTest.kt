package com.offlinegguf.chat.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sandbox shell is plain string assembly; everything here runs on the JVM. */
class SandboxDocumentTest {

    @Test
    fun `web languages are recognised`() {
        assertTrue(SandboxDocument.isWebLanguage("html"))
        assertTrue(SandboxDocument.isWebLanguage("HTML"))
        assertTrue(SandboxDocument.isWebLanguage("js"))
        assertTrue(SandboxDocument.isWebLanguage("JavaScript"))
        assertTrue(SandboxDocument.isWebLanguage("css"))
        assertTrue(SandboxDocument.isWebLanguage("svg"))
        assertFalse(SandboxDocument.isWebLanguage("python"))
        assertFalse(SandboxDocument.isWebLanguage(""))
    }

    @Test
    fun `file names fall back per language and drop paths`() {
        assertEquals("index.html", SandboxDocument.fileNameFor("html", null))
        assertEquals("styles.css", SandboxDocument.fileNameFor("css", null))
        assertEquals("script.js", SandboxDocument.fileNameFor("js", null))
        assertEquals("app.py", SandboxDocument.fileNameFor("python", "src/app.py"))
        assertEquals("page.html", SandboxDocument.fileNameFor("html", "weird\\dir\\page.html"))
    }

    @Test
    fun `html fragments get a shell with charset and viewport`() {
        val doc = SandboxDocument.assemble("html", "<h1>Hi</h1>")
        assertTrue(doc.contains("<!doctype html>"))
        assertTrue(doc.contains("charset"))
        assertTrue(doc.contains("viewport"))
        assertTrue(doc.contains("<h1>Hi</h1>"))
        assertTrue(doc.contains("sandbox-console"))
    }

    @Test
    fun `whole html documents keep their doctype and gain a console hook`() {
        val source = "<!doctype html><html><head><title>t</title></head><body><p>x</p></body></html>"
        val doc = SandboxDocument.assemble("html", source)
        assertTrue(doc.contains("<title>t</title>"))
        assertTrue(doc.contains("sandbox-console"))
        assertTrue(doc.indexOf("sandbox-console") > doc.indexOf("<body>"))
    }

    @Test
    fun `js snippets land inside a script tag and css inside a style tag`() {
        val js = SandboxDocument.assemble("js", "console.log('hi')")
        assertTrue(js.contains("<script>\nconsole.log('hi')\n</script>"))

        val css = SandboxDocument.assemble("css", "body { color: red; }")
        assertTrue(css.contains("<style>\nbody { color: red; }\n</style>"))
        // CSS needs a specimen to style.
        assertTrue(css.contains("<h1>"))
    }

    @Test
    fun `margin pipes in user code survive assembly`() {
        val doc = SandboxDocument.assemble("js", "|not a margin\nconst x = '|';")
        assertTrue(doc.contains("|not a margin"))
        assertTrue(doc.contains("const x = '|';"))
    }
}
