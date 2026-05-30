package dev.weixiao.wxfilemanager.utils

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import io.noties.prism4j.AbsVisitor
import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j

object SyntaxHighlighter {

    private val locator: GrammarLocator? = runCatching {
        val cls = Class.forName("dev.weixiao.wxfilemanager.utils.PrismGrammarLocator")
        cls.getDeclaredConstructor().newInstance() as GrammarLocator
    }.getOrNull()

    private val prism: Prism4j? = locator?.let { Prism4j(it) }

    private val colorMap: Map<String, Int> = mapOf(
        "comment" to 0xFF6A9955.toInt(),
        "prolog" to 0xFF6A9955.toInt(),
        "doctype" to 0xFF6A9955.toInt(),
        "cdata" to 0xFF6A9955.toInt(),

        "string" to 0xFFCE9178.toInt(),
        "char" to 0xFFCE9178.toInt(),
        "attr-value" to 0xFFCE9178.toInt(),
        "url" to 0xFFCE9178.toInt(),

        "keyword" to 0xFF569CD6.toInt(),
        "boolean" to 0xFF569CD6.toInt(),
        "null" to 0xFF569CD6.toInt(),
        "atrule" to 0xFF569CD6.toInt(),
        "important" to 0xFF569CD6.toInt(),

        "number" to 0xFFB5CEA8.toInt(),
        "hexcode" to 0xFFB5CEA8.toInt(),

        "function" to 0xFFDCDCAA.toInt(),
        "class-name" to 0xFF4EC9B0.toInt(),
        "builtin" to 0xFF4EC9B0.toInt(),
        "namespace" to 0xFF4EC9B0.toInt(),

        "tag" to 0xFF569CD6.toInt(),
        "attr-name" to 0xFF9CDCFE.toInt(),
        "selector" to 0xFFD7BA7D.toInt(),
        "property" to 0xFF9CDCFE.toInt(),

        "operator" to 0xFFD4D4D4.toInt(),
        "punctuation" to 0xFFD4D4D4.toInt(),
        "entity" to 0xFFD4D4D4.toInt(),

        "regex" to 0xFFD16969.toInt(),
        "variable" to 0xFF9CDCFE.toInt(),
        "constant" to 0xFF4FC1FF.toInt(),
        "symbol" to 0xFF4FC1FF.toInt(),
        "deleted" to 0xFFCE9178.toInt(),
        "inserted" to 0xFFB5CEA8.toInt()
    )

    /**
     * 根据文件名后缀返回 prism4j 语言标识，未支持则返回 null。
     */
    fun languageOf(filename: String): String? {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) {
            val lower = filename.lowercase()
            return when (lower) {
                "makefile", "dockerfile" -> null
                else -> null
            }
        }
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp" -> "cpp"
            "cs" -> "csharp"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "javascript"
            "json" -> "json"
            "xml", "html", "htm", "vue", "svg" -> "markup"
            "css", "scss", "less" -> "css"
            "py" -> "python"
            "sql" -> "sql"
            "yml", "yaml" -> "yaml"
            "md", "markdown" -> "markdown"
            "sh", "bash", "zsh" -> "bash"
            "go" -> "go"
            "swift" -> "swift"
            "dart" -> "dart"
            "groovy", "gradle" -> "groovy"
            else -> null
        }
    }

    fun highlight(text: String, language: String?): CharSequence {
        val p = prism ?: return text
        val lang = language ?: return text
        val grammar = p.grammar(lang) ?: return text

        return try {
            val nodes = p.tokenize(text, grammar)
            val builder = SpannableStringBuilder()
            val visitor = SpanRenderingVisitor(builder)
            visitor.visit(nodes)
            builder
        } catch (_: Throwable) {
            text
        }
    }

    private class SpanRenderingVisitor(
        private val builder: SpannableStringBuilder
    ) : AbsVisitor() {

        private val typeStack = ArrayDeque<String>()

        override fun visitText(text: Prism4j.Text) {
            val start = builder.length
            builder.append(text.literal())
            val end = builder.length
            val currentType = typeStack.lastOrNull() ?: return
            val color = colorMap[currentType] ?: return
            builder.setSpan(
                ForegroundColorSpan(color),
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (currentType == "keyword" || currentType == "important") {
                builder.setSpan(
                    StyleSpan(android.graphics.Typeface.BOLD),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        override fun visitSyntax(syntax: Prism4j.Syntax) {
            typeStack.addLast(syntax.type())
            try {
                visit(syntax.children())
            } finally {
                typeStack.removeLast()
            }
        }
    }
}
