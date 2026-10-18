package com.offlinegguf.chat.data.catalog

import com.offlinegguf.chat.domain.device.DeviceProfile
import com.offlinegguf.chat.domain.fit.ModelFit
import com.offlinegguf.chat.util.Formats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalogue and the adviser.
 *
 * Nothing here touches the network: entries are scored against a device profile
 * and handed back as links. The last test reads the shipped asset, so a typo in
 * `models.json` fails the build instead of the Discover screen.
 */
class CatalogTest {

    private val GB = 1024L * 1024 * 1024

    private fun device(ramGb: Long = 8, cores: Int = 8, freeGb: Long = 60) = DeviceProfile(
        ramBytes = ramGb * GB,
        javaHeapBytes = 256L * 1024 * 1024,
        largeJavaHeapBytes = 512L * 1024 * 1024,
        cores = cores,
        maxCpuMhz = 2800.0,
        abiList = listOf("arm64-v8a"),
        apiLevel = 34,
        freeStorageBytes = freeGb * GB,
        totalStorageBytes = 128 * GB,
        pageSizeBytes = 4096,
        engineAvailable = true,
    )

    private fun model(
        id: String,
        billions: Double,
        sizeBytes: Long,
        quality: Int,
        uncensored: Boolean = false,
        styles: List<String> = listOf("general"),
        fileHint: String? = null,
        license: String = "Apache-2.0",
        contextLength: Int = 8192,
        blockCount: Int = 28,
        embeddingLength: Int = 2048,
        headCountKv: Int = 4,
    ) = CatalogModel(
        id = id,
        repo = "org/$id-GGUF",
        family = id.replace("-", " "),
        displayName = id,
        paramsBillions = billions,
        quant = "Q4_K_M",
        approxSizeBytes = sizeBytes,
        contextLength = contextLength,
        blockCount = blockCount,
        embeddingLength = embeddingLength,
        headCountKv = headCountKv,
        style = styles,
        languages = listOf("en"),
        license = license,
        uncensored = uncensored,
        qualityScore = quality,
        fileHint = fileHint,
    )

    private val tiny = model("tiny", 0.5, 350_000_000, 30, styles = listOf("general", "chat", "code"), fileHint = "tiny-q4_k_m.gguf")
    private val mid = model("mid", 3.0, 2_000_000_000, 65, styles = listOf("general", "analysis"))
    private val huge = model("huge", 70.0, 40 * GB, 98)
    private val free = model("free", 3.0, 2_000_000_000, 70, uncensored = true, styles = listOf("chat"))
    private val freeSmall = model("free-small", 0.9, 700_000_000, 55, uncensored = true)
    private val catalog = listOf(tiny, mid, huge, free, freeSmall)

    // -------------------------------------------------------------- advising

    @Test
    fun `a model that cannot fit is ranked last whatever its quality`() {
        val ranked = ModelAdvisor.recommend(device(8), catalog, limit = 10)
        assertEquals(catalog.size, ranked.size)
        assertEquals((1..ranked.size).toList(), ranked.map { it.rank })
        assertEquals("huge", ranked.last().model.id)
        assertEquals(ModelFit.Verdict.WILL_NOT_FIT, ranked.last().assessment.verdict)
        // The 98-quality 70B model still loses to a 30-quality 0.5B one.
        assertTrue(ranked.indexOfFirst { it.model.id == "tiny" } < ranked.indexOfLast { it.model.id == "huge" })
        // Scores descend inside the group that fits.
        val fitting = ranked.filter { it.model.id != "huge" }
        assertEquals(fitting.map { it.score }, fitting.map { it.score }.sortedDescending())
    }

    @Test
    fun `the limit is honoured and never returns nothing`() {
        assertEquals(2, ModelAdvisor.recommend(device(8), catalog, limit = 2).size)
        assertEquals(1, ModelAdvisor.recommend(device(8), catalog, limit = 0).size)
    }

    @Test
    fun `the uncensored preference only returns refusal-free models`() {
        val prefs = ModelAdvisor.Prefs(uncensored = true)
        val ranked = ModelAdvisor.recommend(device(8), catalog, prefs, limit = 10)
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.all { it.model.uncensored })
        assertEquals(setOf("free", "free-small"), ranked.map { it.model.id }.toSet())
        assertTrue(ranked.all { it.reasons.any { reason -> reason.contains("Refusal-free") } })
        assertTrue(ranked.all { it.badges.contains("Refusal-free") })
    }

    @Test
    fun `the refusal-free style behaves like the toggle`() {
        val ranked = ModelAdvisor.recommend(device(8), catalog, ModelAdvisor.Prefs(style = ModelStyle.UNCENSORED), limit = 10)
        assertTrue(ranked.all { it.model.uncensored })
    }

    @Test
    fun `the download cap removes files the user would not fetch anyway`() {
        val prefs = ModelAdvisor.Prefs(maxDownloadBytes = 1 * GB)
        val ranked = ModelAdvisor.recommend(device(8), catalog, prefs, limit = 10)
        assertTrue(ranked.all { it.model.approxSizeBytes <= 1 * GB })
        assertEquals(setOf("tiny", "free-small"), ranked.map { it.model.id }.toSet())
    }

    @Test
    fun `a weak phone is steered to something it can actually run`() {
        val best = ModelAdvisor.best(device(3, cores = 4), catalog)
        assertNotNull(best)
        assertTrue(best!!.assessment.usable)
        assertTrue(
            "a 3 GB phone was offered ${best.model.id} at ${best.model.approxSizeBytes} bytes",
            best.model.approxSizeBytes <= 1 * GB,
        )
        // The 70B entry is never the top pick on a small phone.
        assertFalse(best.model.id == "huge")
    }

    @Test
    fun `best is the top of recommend`() {
        val prefs = ModelAdvisor.Prefs(style = ModelStyle.CODE)
        assertEquals(
            ModelAdvisor.recommend(device(8), catalog, prefs, limit = 1).first().model.id,
            ModelAdvisor.best(device(8), catalog, prefs)!!.model.id,
        )
    }

    @Test
    fun `a code preference explains itself`() {
        val ranked = ModelAdvisor.recommend(device(8), catalog, ModelAdvisor.Prefs(style = ModelStyle.CODE), limit = 10)
        val codeModel = ranked.first { it.model.id == "tiny" }
        assertTrue(codeModel.reasons.any { it.contains("Code-tuned") })
        assertTrue(codeModel.badges.contains("Code"))
    }

    @Test
    fun `reasons and badges are never empty`() {
        val ranked = ModelAdvisor.recommend(device(8), catalog, limit = 10)
        assertTrue(ranked.all { it.reasons.isNotEmpty() })
        assertTrue(ranked.all { it.badges.isNotEmpty() })
        assertTrue(ranked.last().badges.contains("Too big"))
        assertTrue(ranked.last().reasons.any { it.startsWith("Needs ~") })
        assertTrue(ranked.first { it.model.id == "tiny" }.badges.contains("Under 1 GB"))
        assertTrue(ranked.first { it.model.id == "mid" }.reasons.any { it.contains("tok/s") })
    }

    @Test
    fun `a tight device gets told when the trained context will not fit`() {
        val ranked = ModelAdvisor.recommend(
            device(3, cores = 4),
            listOf(model("longctx", 1.0, 800_000_000, 50, contextLength = 131072)),
            limit = 1,
        )
        assertTrue(ranked.single().reasons.any { it.contains("Trained at") })
    }

    @Test
    fun `a non-apache licence is mentioned`() {
        val ranked = ModelAdvisor.recommend(
            device(8),
            listOf(model("llama-ish", 3.0, 2_000_000_000, 60, license = "Llama 3 Community")),
            limit = 1,
        )
        assertTrue(ranked.single().reasons.any { it.contains("Licence: Llama 3 Community") })
    }

    @Test
    fun `an empty catalogue degrades instead of crashing`() {
        assertTrue(ModelAdvisor.recommend(device(8), emptyList()).isEmpty())
        assertNull(ModelAdvisor.best(device(8), emptyList()))
        assertEquals("No catalogue entries available.", ModelAdvisor.headline(device(8), emptyList()))
    }

    @Test
    fun `the headline names the model, its size and its speed`() {
        val best = ModelAdvisor.best(device(8), catalog)!!
        val headline = ModelAdvisor.headline(device(8), catalog)
        assertTrue(headline, headline.startsWith(best.model.displayName))
        assertTrue(headline, headline.contains(Formats.bytes(best.model.approxSizeBytes)))
        assertTrue(headline, headline.contains("tok/s"))
    }

    @Test
    fun `low storage is called out`() {
        val ranked = ModelAdvisor.recommend(device(8, freeGb = 2), listOf(mid), limit = 1)
        assertTrue(ranked.single().reasons.any { it.contains("free; importing wants") })
        assertTrue(ranked.single().badges.contains("Low storage"))
    }

    // -------------------------------------------------------------- catalogue

    @Test
    fun `urls point at the file, the tree, the card and a search`() {
        assertEquals("https://huggingface.co/org/tiny-GGUF", tiny.modelCardUrl)
        assertEquals("https://huggingface.co/org/tiny-GGUF/tree/main", tiny.repoUrl)
        assertEquals(
            "https://huggingface.co/org/tiny-GGUF/resolve/main/tiny-q4_k_m.gguf",
            tiny.primaryUrl,
        )
        // Without a known file name the tree is the honest link.
        assertEquals(mid.repoUrl, mid.primaryUrl)
        assertEquals(
            "https://huggingface.co/models?search=mid%20GGUF",
            mid.searchUrl,
        )
        assertEquals(
            "https://huggingface.co/org/tiny-GGUF/resolve/main/a%20b.gguf",
            tiny.fileUrl("a b.gguf"),
        )
    }

    @Test
    fun `matches applies the preferences`() {
        assertFalse(tiny.matches(ModelAdvisor.Prefs(uncensored = true)))
        assertTrue(free.matches(ModelAdvisor.Prefs(uncensored = true)))
        assertFalse(tiny.matches(ModelAdvisor.Prefs(maxDownloadBytes = 100)))
        assertTrue(tiny.matches(ModelAdvisor.Prefs(style = ModelStyle.CHAT)))
        assertTrue(tiny.hasStyle("code"))
        assertFalse(tiny.hasStyle("analysis"))
        assertEquals(500_000_000L, tiny.paramCount)
    }

    @Test
    fun `styles resolve by id and fall back to general`() {
        assertEquals(ModelStyle.CODE, ModelStyle.fromId("code"))
        assertEquals(ModelStyle.CODE, ModelStyle.fromId("CODE"))
        assertEquals(ModelStyle.GENERAL, ModelStyle.fromId("nonsense"))
        assertEquals(ModelStyle.GENERAL, ModelStyle.fromId(null))
        assertEquals(ModelStyle.UNCENSORED, ModelStyle.fromId("uncensored"))
        assertTrue(ModelStyle.values().size >= 5)
    }

    @Test
    fun `json parses, and bad json returns null instead of throwing`() {
        val text = """
            {
              "catalogVersion": 7,
              "updated": "2026-01-01",
              "note": "test",
              "entries": [
                { "id": "a", "repo": "org/a", "displayName": "A", "approxSizeBytes": 100, "qualityScore": 10 },
                { "id": "b", "repo": "org/b", "displayName": "B", "approxSizeBytes": 200, "uncensored": true }
              ]
            }
        """.trimIndent()
        val parsed = CatalogStore.parse(text)
        assertEquals(7, parsed.catalogVersion)
        assertEquals("2026-01-01", parsed.updated)
        assertEquals(2, parsed.entries.size)
        assertEquals("a", parsed.entries[0].id)
        assertEquals(100L, parsed.entries[0].approxSizeBytes)
        // Everything absent from the JSON takes its default.
        assertEquals(50, parsed.entries[0].qualityScore)
        assertEquals(4096, parsed.entries[0].contextLength)
        assertFalse(parsed.entries[0].uncensored)
        assertTrue(parsed.entries[1].uncensored)
        assertEquals(listOf(parsed.entries[1]), parsed.uncensored())
        assertEquals(parsed.entries[0], parsed.byId("a"))
        assertNull(parsed.byId("missing"))
        assertFalse(parsed.isEmpty)
        assertTrue(ModelCatalog().isEmpty)

        assertNull(CatalogStore.parseOrNull("this is not json"))
        assertNull(CatalogStore.parseOrNull("{"))
    }

    @Test
    fun `the shipped catalogue is well formed`() {
        // ASSET_PATH already contains "catalog/", so this must not add it again —
        // a wrong path here makes assumeTrue skip the test silently, which is how
        // the shipped asset stops being validated at all.
        val file = File("src/main/assets/${CatalogStore.ASSET_PATH}")
        assertTrue("catalogue asset not found at ${file.path}", file.exists())

        val parsed = CatalogStore.parseOrNull(file.readText())
        assertNotNull("the shipped catalogue must parse", parsed)
        val entries = parsed!!.entries
        assertTrue("expected a real shortlist, found ${entries.size}", entries.size >= 12)
        assertEquals(entries.size, entries.map { it.id }.distinct().size)

        for (entry in entries) {
            assertTrue(entry.id, entry.repo.contains("/"))
            assertTrue(entry.id, entry.displayName.isNotBlank())
            assertTrue(entry.id, entry.approxSizeBytes > 0)
            assertTrue(entry.id, entry.paramsBillions > 0)
            assertTrue(entry.id, entry.contextLength >= 2048)
            assertTrue(entry.id, entry.qualityScore in 0..100)
            assertTrue(entry.id, entry.license.isNotBlank())
            assertTrue(entry.id, entry.notes.isNotBlank())
            assertTrue(entry.id, entry.primaryUrl.startsWith("https://huggingface.co/"))
            assertTrue(entry.id, entry.searchUrl.startsWith("https://huggingface.co/models?search="))
        }

        // The refusal-free category is part of the product, so it must be stocked.
        val uncensored = entries.filter { it.uncensored }
        assertTrue("expected refusal-free entries, found ${uncensored.size}", uncensored.size >= 3)
        assertTrue(uncensored.all { it.notes.isNotBlank() })

        // Every tier of phone has something it can actually run.
        for (ramGb in listOf(3L, 6L, 12L)) {
            val ranked = ModelAdvisor.recommend(device(ramGb), entries, limit = 3)
            assertTrue("no recommendation for a $ramGb GB phone", ranked.isNotEmpty())
            assertTrue(
                "the top pick for a $ramGb GB phone does not fit",
                ranked.first().assessment.verdict != ModelFit.Verdict.WILL_NOT_FIT,
            )
        }
        // And with the uncensored preference, on a mid phone.
        // Refusal-free entries are 7B-class, so they need a bigger phone; on a
        // 12 GB device the top one still has to be runnable.
        val uncensoredRanked = ModelAdvisor.recommend(
            device(12),
            entries,
            ModelAdvisor.Prefs(uncensored = true, preferSpeed = true),
            limit = 3,
        )
        assertTrue(uncensoredRanked.isNotEmpty())
        assertTrue(uncensoredRanked.all { it.model.uncensored })
        assertTrue(uncensoredRanked.first().assessment.usable)
    }
}
