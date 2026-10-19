package com.offlinegguf.chat.domain.skills

/**
 * A small validator for GBNF grammars — the constrained-decoding format
 * llama.cpp uses.
 *
 * It is not a full parser. It catches the mistakes that actually break a
 * generation (missing `root`, unbalanced quotes/brackets, a rule that is
 * referenced but never defined) and explains them in plain language, which is
 * what the skill editor needs. A grammar that passes here can still be rejected
 * by the engine; the engine's own message is surfaced when that happens.
 */
object Gbnf {

    data class Result(val valid: Boolean, val errors: List<String>, val ruleCount: Int) {
        companion object {
            val Empty = Result(false, listOf("grammar is empty"), 0)
        }
    }

    private val DEFINITION = Regex("^\\s*([A-Za-z_][A-Za-z0-9_-]*)\\s*::=(.*)$")
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_-]*")

    fun validate(grammar: String?): Result {
        val text = grammar?.trim().orEmpty()
        if (text.isEmpty()) return Result(false, listOf("The grammar is empty."), 0)

        val errors = ArrayList<String>()
        val defined = LinkedHashSet<String>()
        val referenced = LinkedHashSet<String>()

        // Line comments run from '#' to end of line.
        val lines = text.split('\n').map { stripComment(it) }

        var bracketDepth = 0
        var parenDepth = 0

        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            val number = index + 1

            val definition = DEFINITION.matchEntire(line)
            val body = if (definition != null) {
                defined += definition.groupValues[1]
                definition.groupValues[2]
            } else {
                // Continuation of the previous rule.
                line
            }

            val literals = extractLiterals(body)
            if (literals.unterminatedString) {
                errors += "Line $number: a \" is never closed."
            }
            if (literals.unterminatedClass) {
                errors += "Line $number: a character class [ is never closed."
            }
            bracketDepth += literals.bracketDelta
            parenDepth += literals.parenDelta
            referenced += literals.identifiers

            if (body.contains("::=") && definition == null) {
                errors += "Line $number: '::=' must start a rule name (for example: value ::= ...)."
            }
        }

        if (bracketDepth > 0) errors += "Unclosed '[' — $bracketDepth open at the end of the grammar."
        if (bracketDepth < 0) errors += "Unmatched ']' — ${-bracketDepth} more closes than opens."
        if (parenDepth > 0) errors += "Unclosed '(' — $parenDepth open at the end of the grammar."
        if (parenDepth < 0) errors += "Unmatched ')' — ${-parenDepth} more closes than opens."

        if ("root" !in defined) {
            errors += "No 'root' rule. Constrained decoding starts at 'root ::= …'."
        }
        val undefined = referenced - defined
        for (name in undefined) {
            errors += "'$name' is used but never defined."
            if (errors.size > 12) break
        }
        return Result(errors.isEmpty(), errors, defined.size)
    }

    /** Removes a `#` comment, ignoring `#` inside a string or character class. */
    private fun stripComment(line: String): String {
        var inString = false
        var inClass = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if ((inString || inClass) && c == '\\') {
                i += 2 // skip the escaped character
                continue
            }
            when {
                inString && c == '"' -> inString = false
                !inString && !inClass && c == '"' -> inString = true
                inClass && c == ']' -> inClass = false
                !inString && !inClass && c == '[' -> inClass = true
                !inString && !inClass && c == '#' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private class Scan(
        val identifiers: Set<String>,
        val bracketDelta: Int,
        val parenDelta: Int,
        val unterminatedString: Boolean,
        val unterminatedClass: Boolean,
    )

    /** Walks a rule body, skipping over string literals and character classes. */
    private fun extractLiterals(body: String): Scan {
        val ids = LinkedHashSet<String>()
        var brackets = 0
        var parens = 0
        var i = 0
        var openString = false
        var openClass = false
        while (i < body.length) {
            val c = body[i]
            when {
                openString -> {
                    if (c == '\\') i++
                    else if (c == '"') openString = false
                }
                openClass -> {
                    if (c == '\\') i++
                    // Closing a character class balances the '[' that opened it;
                    // without this every grammar with a class looks unclosed.
                    else if (c == ']') {
                        openClass = false
                        brackets--
                    }
                }
                c == '"' -> openString = true
                c == '[' -> {
                    openClass = true
                    brackets++
                }
                c == ']' -> brackets--
                c == '(' -> parens++
                c == ')' -> parens--
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < body.length && (body[i].isLetterOrDigit() || body[i] == '_' || body[i] == '-')) i++
                    ids += body.substring(start, i)
                    continue // `i` already advanced
                }
            }
            i++
        }
        return Scan(ids, brackets, parens, openString, openClass)
    }
}
