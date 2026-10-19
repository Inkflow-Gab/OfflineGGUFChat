package com.offlinegguf.chat.domain.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinSkillsTest {

    @Test
    fun `person is a built in strict correctness focused developer`() {
        val person = BuiltinSkills.bySlug("person") ?: throw AssertionError("person skill is missing")

        assertEquals("person", person.name)
        assertTrue(person.builtin)
        assertTrue(person.systemPrompt.contains("Gab"))
        assertTrue(person.systemPrompt.contains("17-year-old senior high school student"))
        assertTrue(person.systemPrompt.contains("correctness-focused"))
        assertTrue(person.systemPrompt.contains("Never invent test results"))
        assertTrue(person.systemPrompt.contains("no insults"))
        assertFalse(person.systemPrompt.contains("Ignore all rules"))
    }
}
