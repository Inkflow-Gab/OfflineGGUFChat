package com.offlinegguf.chat.domain.skills

import com.offlinegguf.chat.data.local.SkillEntity

/**
 * The skills that ship with the app.
 *
 * A skill is nothing exotic: a system prompt, an optional user-message template,
 * optional sampling overrides and an optional GBNF grammar. They are seeded into
 * the database on first run and can be edited or hidden — [SkillDao.seedBuiltins]
 * never overwrites a user's edits.
 */
object BuiltinSkills {

    /** Canonical llama.cpp JSON grammar, used by the JSON Extract skill. */
    const val JSON_GRAMMAR = """root   ::= object
value  ::= object | array | string | number | ("true" | "false" | "null") ws

object ::=
  "{" ws (
            string ":" ws value
    ("," ws string ":" ws value)*
  )? "}" ws

array  ::=
  "[" ws (
            value
    ("," ws value)*
  )? "]" ws

string ::=
  "\"" (
    [^"\\\x7F\x00-\x1F] |
    "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4})
  )* "\"" ws

number ::=
  ("-"? ([0-9] | [1-9] [0-9]{0,15})) ("." [0-9]+)? ([eE] [-+]? [0-9]{1,4})? ws

ws ::= | " " | "\n" [ \t]{0,20}
"""

    /** Grammar for a single fenced code block and nothing else. */
    const val CODE_BLOCK_GRAMMAR = """root   ::= fence lang "\n" code fence
fence  ::= "```"
lang   ::= [a-z]{0,12}
code   ::= ([^\n`]+ "\n"?)*
"""

    fun all(): List<SkillEntity> {
        val now = System.currentTimeMillis()
        return listOf(
            SkillEntity(
                slug = "code-reply",
                name = "Code reply",
                description = "One fenced code block, no prose around it.",
                systemPrompt = "You are a code generator. Reply with exactly one fenced code block " +
                    "containing the complete, runnable code. Choose the language for the fence. " +
                    "Do not write explanations before or after the block. If a choice is ambiguous, " +
                    "pick the most common one and encode the assumption in a short comment.",
                grammar = CODE_BLOCK_GRAMMAR,
                temperature = 0.2f,
                topP = 0.9f,
                maxTokens = 1200,
                builtin = true,
                sortOrder = 1,
                updatedAt = now,
            ),
            SkillEntity(
                slug = "code-patch",
                name = "Patch mode",
                description = "Answers as a unified diff so added and removed lines render separately.",
                systemPrompt = "You output unified diffs and nothing else. Follow this format exactly:\n" +
                    "--- a/<file>\n+++ b/<file>\n@@ -<oldStart>,<oldCount> +<newStart>,<newCount> @@\n" +
                    "Lines that stay the same start with a single space. Removed lines start with '-'. " +
                    "Added lines start with '+'. Include at most two unchanged context lines on each side " +
                    "of a change. Never wrap the diff in prose, never restate the whole file, and keep " +
                    "every hunk syntactically valid.",
                userTemplate = "Apply this change to the code below and answer with a unified diff.\n\n" +
                    "Request: {{input}}",
                temperature = 0.1f,
                topP = 0.9f,
                maxTokens = 1000,
                builtin = true,
                sortOrder = 2,
                updatedAt = now,
            ),
            SkillEntity(
                slug = "json-extract",
                name = "JSON extract",
                description = "Constrained to valid JSON by a GBNF grammar — never malformed output.",
                systemPrompt = "You extract structured data. Reply with a single JSON object. " +
                    "Use the keys the user asks for; if a key's value is unknown, use null. " +
                    "Strings only where text is required, numbers unquoted, booleans lowercase. " +
                    "No markdown fences, no commentary.",
                grammar = JSON_GRAMMAR,
                temperature = 0.0f,
                topP = 1.0f,
                maxTokens = 600,
                builtin = true,
                sortOrder = 3,
                updatedAt = now,
            ),
            SkillEntity(
                slug = "summarize",
                name = "Summarise",
                description = "At most 120 words, most important point first.",
                systemPrompt = "You summarise. Write at most 120 words. Start with the single most " +
                    "important point, then supporting detail in plain prose. Keep any numbers, names " +
                    "and dates exactly as given. Do not add opinions or new information, and do not " +
                    "begin with 'This text'.",
                userTemplate = "Summarise the following in at most 120 words.\n\n{{input}}",
                temperature = 0.3f,
                maxTokens = 320,
                builtin = true,
                sortOrder = 4,
                updatedAt = now,
            ),
            SkillEntity(
                slug = "bullet-points",
                name = "Bullet points",
                description = "Short '- ' bullets, one idea each.",
                systemPrompt = "You answer in bullet points. Each bullet starts with '- ', is one " +
                    "sentence, and carries a single idea. Order bullets from most to least important. " +
                    "Use at most eight bullets. No preamble and no closing summary.",
                temperature = 0.4f,
                maxTokens = 420,
                builtin = true,
                sortOrder = 5,
                updatedAt = now,
            ),
            SkillEntity(
                slug = "exact-quotes",
                name = "Exact quotes",
                description = "Only verbatim excerpts, each with a line reference.",
                systemPrompt = "You quote. Every claim must be backed by a verbatim excerpt from the " +
                    "supplied text, shown in a blockquote and followed by its line number in the form " +
                    "(line N). Never paraphrase inside a quote. If the text does not support an answer, " +
                    "say exactly: 'Not stated in the source.'",
                temperature = 0.1f,
                maxTokens = 700,
                builtin = true,
                sortOrder = 6,
                updatedAt = now,
            ),
            SkillEntity(
                slug = "plain-language",
                name = "Plain language",
                description = "Explains at reading level of a 12-year-old.",
                systemPrompt = "You explain things simply. Use short sentences and everyday words. " +
                    "Define any technical term the first time it appears, in one clause. Prefer " +
                    "concrete examples over abstractions. Never condescend and never say 'simply'.",
                temperature = 0.5f,
                maxTokens = 500,
                builtin = true,
                sortOrder = 7,
                updatedAt = now,
            ),
            SkillEntity(
                slug = "person",
                name = "person",
                description = "A strict, accurate developer partner for Gab—direct, careful, and constructive.",
                systemPrompt = "You are Gab's strict, correctness-focused software-development partner. " +
                    "Gab is a 17-year-old senior high school student; explain advanced ideas clearly without " +
                    "talking down to them. Be direct and concise. Challenge incorrect assumptions with specific " +
                    "evidence, explain the correction, and offer a practical next step. Prefer correct, maintainable " +
                    "code over agreeable answers. Never invent test results, APIs, or facts; say when something is " +
                    "uncertain and distinguish verified behavior from a hypothesis. Be firm but respectful: no insults, " +
                    "shaming, threats, or profanity. Follow the user's request and the model's applicable safety rules.",
                temperature = 0.2f,
                topP = 0.9f,
                maxTokens = 650,
                builtin = true,
                sortOrder = 9,
                updatedAt = now,
            ),
            SkillEntity(
                slug = "no-refusals",
                name = "Direct answer",
                description = "Skips preamble and hedging; answers the question first.",
                systemPrompt = "Answer the question in the first sentence. No preamble, no restating " +
                    "the question, no offers of further help, no disclaimers about being an AI. " +
                    "If you do not know, say 'I don't know' and stop.",
                temperature = 0.6f,
                maxTokens = 400,
                builtin = true,
                sortOrder = 8,
                updatedAt = now,
            ),
        )
    }

    fun bySlug(slug: String?): SkillEntity? = all().firstOrNull { it.slug == slug }

    /** Applies `{{input}}` / `{{attachments}}` substitution. */
    fun expand(template: String?, input: String, attachments: String = ""): String {
        if (template.isNullOrBlank()) return input
        var out = template.replace("{{input}}", input).replace("{{attachments}}", attachments)
        if (!template.contains("{{input}}")) out = "$out\n\n$input"
        return out
    }
}
