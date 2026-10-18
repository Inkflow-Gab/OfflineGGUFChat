package com.offlinegguf.chat.utils

import android.content.Context
import com.offlinegguf.chat.domain.model.Skill
import com.offlinegguf.chat.domain.model.SkillType
import java.io.File

object SkillsManager {

    val builtInGrammars: Map<String, String> = mapOf(
        "json" => """
            root  ::= object
            object ::= "{" [ws] ("}" [ws] | pair [ws] ("," [ws] pair [ws])* "}" [ws])
            pair   ::= string [ws] ":" [ws] value
            value  ::= string | number | object | array | "true" [ws] | "false" [ws] | "null" [ws]
            array  ::= "[" [ws] ("]" [ws] | value [ws] ("," [ws] value [ws])* "]" [ws])
            string ::= "\"" [ws] (char [ws])* "\"" [ws]
            char   ::= [^"\\] | "\\" ["\\/bfnrt] | "\\" "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
            number ::= ["-"] [0-9] [0-9]* ["." [0-9]+]? [eE] ["-"]? [0-9]+?
            ws     ::= [ \t\n\r]*
        """.trimIndent(),
        "sql" => """
            root ::= select_stmt
            select_stmt ::= "SELECT" [ws] column_list [ws] "FROM" [ws] table_name [ws] ("WHERE" [ws] condition)? ";"
            column_list ::= "*" | column ("," column)*
            column ::= [a-zA-Z_][a-zA-Z0-9_]*
            table_name ::= [a-zA-Z_][a-zA-Z0-9_]*
            condition ::= column [ws] "=" [ws] value
            value ::= string | number
            string ::= "'" [^']* "'"
            number ::= [0-9]+
            ws ::= [ \t\n\r]*
        """.trimIndent(),
        "list" => """
            root ::= item ("\n" item)*
            item ::= "- " [^\n]+
        """.trimIndent()
    )

    val builtInSystemPrompts: Map<String, String> = mapOf(
        "helpful" => "You are a helpful, harmless, and honest AI assistant.",
        "concise" => "You are a concise assistant. Answer briefly and to the point.",
        "coder" => "You are an expert programmer. Write clean, well-commented code.",
        "creative" => "You are a creative writing assistant. Be imaginative and expressive."
    )

    fun validateSkill(skill: Skill): Boolean {
        if (!skill.enabled) return true
        return when (skill.type) {
            SkillType.LORA -> skill.path?.let { File(it).exists() } ?: false
            SkillType.GRAMMAR -> skill.path?.let { File(it).exists() } ?: skill.content?.isNotEmpty() == true
            SkillType.SYSTEM -> skill.content?.isNotEmpty() == true
        }
    }

    fun skillSummary(skill: Skill): String {
        return when (skill.type) {
            SkillType.LORA -> "LoRA adapter (scale=${"%.2f".format(skill.scale)})"
            SkillType.GRAMMAR -> "Grammar: ${skill.name}"
            SkillType.SYSTEM -> "System: ${skill.name}"
        }
    }
}
