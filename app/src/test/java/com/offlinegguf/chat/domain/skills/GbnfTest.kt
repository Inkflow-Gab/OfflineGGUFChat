package com.offlinegguf.chat.domain.skills

import com.offlinegguf.chat.data.repository.SkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grammar validator is what stops a user saving a skill that would make the
 * engine refuse the generation. It is deliberately not a full GBNF parser — these
 * tests pin the mistakes it must catch and the wording it must use.
 */
class GbnfTest {

    private fun errors(grammar: String) = Gbnf.validate(grammar).errors

    @Test
    fun `the bundled grammars validate`() {
        val json = Gbnf.validate(BuiltinSkills.JSON_GRAMMAR)
        assertTrue(json.errors.joinToString("; "), json.valid)
        assertTrue(json.ruleCount > 3)

        val code = Gbnf.validate(BuiltinSkills.CODE_BLOCK_GRAMMAR)
        assertTrue(code.errors.joinToString("; "), code.valid)
    }

    @Test
    fun `an empty grammar says so`() {
        val result = Gbnf.validate(null)
        assertFalse(result.valid)
        assertEquals(listOf("The grammar is empty."), result.errors)
        assertEquals(0, result.ruleCount)
        assertFalse(Gbnf.validate("   ").valid)
    }

    @Test
    fun `a missing root is reported`() {
        val result = Gbnf.validate("value ::= \"a\"")
        assertFalse(result.valid)
        assertEquals(1, result.ruleCount)
        assertTrue(result.errors.any { it.startsWith("No 'root' rule") })
    }

    @Test
    fun `an undefined rule is named`() {
        val result = Gbnf.validate("root ::= thing")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it == "'thing' is used but never defined." })
    }

    @Test
    fun `a defined rule is not reported as missing`() {
        val result = Gbnf.validate("root ::= value\nvalue ::= \"a\" | \"b\"")
        assertTrue(result.errors.joinToString("; "), result.valid)
        assertEquals(2, result.ruleCount)
    }

    @Test
    fun `unbalanced delimiters are explained`() {
        assertTrue(errors("root ::= \"abc").any { it.contains("never closed") })
        assertTrue(errors("root ::= [a-z").any { it.contains("Unclosed '['") })
        assertTrue(errors("root ::= [a-z").any { it.contains("never closed") })
        assertTrue(errors("root ::= (\"a\"").any { it.contains("Unclosed '('") })
        assertTrue(errors("root ::= \"a\")").any { it.contains("Unmatched ')'") })
        assertTrue(errors("root ::= \"a\"]").any { it.contains("Unmatched ']'") })
    }

    @Test
    fun `a balanced grammar with classes and groups passes`() {
        val grammar = """
            root   ::= "{" ws pair ws "}"
            pair   ::= string ":" ws value
            value  ::= string | number
            string ::= "\"" [a-z]* "\""
            number ::= [0-9]+
            ws     ::= [ \t\n]*
        """.trimIndent()
        val result = Gbnf.validate(grammar)
        assertTrue(result.errors.joinToString("; "), result.valid)
        assertEquals(6, result.ruleCount)
    }

    @Test
    fun `a stray definition operator is explained`() {
        assertTrue(
            errors("root ::= \"a\"\n1bad ::= \"b\"").any { it.contains("must start a rule name") },
        )
    }

    @Test
    fun `comments are stripped, including a hash inside a string`() {
        assertTrue(Gbnf.validate("root ::= \"a\" # a comment").valid)
        assertTrue(Gbnf.validate("root ::= \"#\"").valid)
        assertTrue(Gbnf.validate("# only a comment\nroot ::= \"a\"").valid)
    }

    @Test
    fun `identifiers inside strings are not treated as rules`() {
        val result = Gbnf.validate("root ::= \"undefined_rule_name\"")
        assertTrue(result.errors.joinToString("; "), result.valid)
    }

    @Test
    fun `the repository helper returns the first problem or null`() {
        assertNull(SkillRepository.validateGrammar(null))
        assertNull(SkillRepository.validateGrammar(""))
        assertNull(SkillRepository.validateGrammar("   "))
        assertNull(SkillRepository.validateGrammar(BuiltinSkills.JSON_GRAMMAR))
        val problem = SkillRepository.validateGrammar("value ::= \"a\"")
        assertTrue(problem!!.startsWith("No 'root' rule"))
    }

    @Test
    fun `the chip label falls back for a missing skill`() {
        assertEquals("No skill", SkillRepository.chipLabel(null))
        assertEquals("Patch mode", SkillRepository.chipLabel(BuiltinSkills.bySlug("code-patch")))
    }
}
