package com.milenote.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 轻量 Markdown 渲染（只读，不引入第三方依赖）。
 *
 * 说明：
 * - 只覆盖 GitHub Release 说明常用的子集：标题、段落、有序/无序列表、引用、围栏代码块、分隔线，
 *   行内支持 `**粗体**`、`*斜体*`、`~~删除线~~`、`` `代码` ``、`[链接](url)` 与 `<https://…>` 自动链接。
 * - 解析是纯 Kotlin（[parseMarkdown] / [parseInlineMarkdown]），可单独做 JVM 单测；渲染用 Compose 原生
 *   `Text` + `AnnotatedString`，链接沿用系统 UriHandler（点击用默认浏览器打开）。
 * - 不追求 CommonMark 全覆盖：表格、HTML、图片按普通文本处理，够展示发版说明即可。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val scheme = MaterialTheme.colorScheme
    val linkStyle = remember(scheme.primary) {
        SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline)
    }
    val codeBackground = scheme.surfaceVariant

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inline(block.text, linkStyle, codeBackground),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                )

                is MarkdownBlock.Paragraph -> Text(
                    text = inline(block.text, linkStyle, codeBackground),
                    style = style,
                    color = color,
                )

                is MarkdownBlock.BulletList -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    block.items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "•",
                                style = style,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(16.dp),
                            )
                            Text(
                                text = inline(item, linkStyle, codeBackground),
                                style = style,
                                color = color,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MarkdownBlock.OrderedList -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    block.items.forEachIndexed { index, item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${block.start + index}.",
                                style = style,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(20.dp),
                            )
                            Text(
                                text = inline(item, linkStyle, codeBackground),
                                style = style,
                                color = color,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MarkdownBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(2.dp),
                        ),
                    )
                    Text(
                        text = inline(block.text, linkStyle, codeBackground),
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MarkdownBlock.CodeBlock -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                MarkdownBlock.Rule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 1.dp,
                )
            }
        }
    }
}

@Composable
private fun inline(source: String, linkStyle: SpanStyle, codeBackground: Color): AnnotatedString =
    remember(source, linkStyle, codeBackground) {
        buildAnnotatedString {
            parseInlineMarkdown(source).forEach { span ->
                val spanStyle = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBackground else Color.Unspecified,
                )
                val link = span.url?.let {
                    LinkAnnotation.Url(url = it, styles = TextLinkStyles(style = linkStyle))
                }
                if (link != null) {
                    withLink(link) { withStyle(spanStyle) { append(span.text) } }
                } else {
                    withStyle(spanStyle) { append(span.text) }
                }
            }
        }
    }

// ---------------------------------------------------------------------------------------------
// 解析（纯 Kotlin，可单测）
// ---------------------------------------------------------------------------------------------

/** Markdown 块级元素。行内样式不在这里展开，渲染时再按 [MarkdownSpan] 处理。 */
internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletList(val items: List<String>) : MarkdownBlock
    data class OrderedList(val start: Int, val items: List<String>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class CodeBlock(val code: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

/** 行内样式片段；同一片段可能同时带粗体 + 行内代码等组合。 */
internal data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

private val HeadingPattern = Regex("^(#{1,6})\\s+(.*)$")
private val RulePattern = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val BulletPattern = Regex("^[-*+]\\s+(.*)$")
private val OrderedPattern = Regex("^(\\d{1,3})[.)]\\s+(.*)$")
private val FencePattern = Regex("^```.*$")

/**
 * 把 Markdown 源码切块。空行结束段落/列表/引用；围栏代码块原样保留；
 * 列表项与段落里的软换行按中英文习惯合并（中文/全角标点之间不加空格，英文之间加一个空格）。
 */
internal fun parseMarkdown(source: String): List<MarkdownBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    var bullets: MutableList<String>? = null
    var ordered: MutableList<String>? = null
    var orderedStart = 1
    var quote: MutableList<String>? = null

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
    }

    fun flushLists() {
        bullets?.let {
            if (it.isNotEmpty()) blocks += MarkdownBlock.BulletList(it.toList())
            bullets = null
        }
        ordered?.let {
            if (it.isNotEmpty()) blocks += MarkdownBlock.OrderedList(orderedStart, it.toList())
            ordered = null
        }
        quote?.let {
            if (it.isNotEmpty()) blocks += MarkdownBlock.Quote(it.joinToString("\n"))
            quote = null
        }
    }

    fun flushAll() {
        flushParagraph()
        flushLists()
    }

    var index = 0
    while (index < lines.size) {
        val raw = lines[index]
        val trimmed = raw.trim()
        when {
            trimmed.isEmpty() -> {
                flushAll()
                index++
            }

            FencePattern.matches(trimmed) -> {
                flushAll()
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code += lines[index]
                    index++
                }
                blocks += MarkdownBlock.CodeBlock(code.joinToString("\n"))
                // 跳过收尾的 ```（若有）；没闭合时 index 已经到末尾。
                index++
            }

            HeadingPattern.matches(trimmed) -> {
                flushAll()
                val match = HeadingPattern.matchEntire(trimmed)!!
                blocks += MarkdownBlock.Heading(
                    level = match.groupValues[1].length,
                    text = match.groupValues[2].trim().trimEnd('#').trim(),
                )
                index++
            }

            RulePattern.matches(trimmed) -> {
                flushAll()
                blocks += MarkdownBlock.Rule
                index++
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                bullets?.let {
                    if (it.isNotEmpty()) blocks += MarkdownBlock.BulletList(it.toList())
                    bullets = null
                }
                ordered?.let {
                    if (it.isNotEmpty()) blocks += MarkdownBlock.OrderedList(orderedStart, it.toList())
                    ordered = null
                }
                if (quote == null) quote = mutableListOf()
                quote!! += trimmed.removePrefix(">").trimStart()
                index++
            }

            BulletPattern.matches(trimmed) -> {
                flushParagraph()
                ordered?.let {
                    if (it.isNotEmpty()) blocks += MarkdownBlock.OrderedList(orderedStart, it.toList())
                    ordered = null
                }
                if (bullets == null) bullets = mutableListOf()
                bullets!! += BulletPattern.matchEntire(trimmed)!!.groupValues[1].trim()
                index++
            }

            OrderedPattern.matches(trimmed) -> {
                flushParagraph()
                bullets?.let {
                    if (it.isNotEmpty()) blocks += MarkdownBlock.BulletList(it.toList())
                    bullets = null
                }
                val match = OrderedPattern.matchEntire(trimmed)!!
                if (ordered == null) {
                    ordered = mutableListOf()
                    orderedStart = match.groupValues[1].toIntOrNull() ?: 1
                }
                ordered!! += match.groupValues[2].trim()
                index++
            }

            // 缩进行 = 上一个元素的续行（Release 说明里列表项换行后的缩进）。
            raw.startsWith(" ") || raw.startsWith("\t") -> {
                when {
                    quote != null -> quote!![quote!!.lastIndex] =
                        softJoin(quote!!.last(), trimmed)

                    bullets != null -> bullets!![bullets!!.lastIndex] =
                        softJoin(bullets!!.last(), trimmed)

                    ordered != null -> ordered!![ordered!!.lastIndex] =
                        softJoin(ordered!!.last(), trimmed)

                    paragraph.isNotEmpty() -> {
                        val joined = softJoin(paragraph.toString(), trimmed)
                        paragraph.clear()
                        paragraph.append(joined)
                    }

                    else -> paragraph.append(trimmed)
                }
                index++
            }

            else -> {
                flushLists()
                if (paragraph.isEmpty()) {
                    paragraph.append(trimmed)
                } else {
                    val joined = softJoin(paragraph.toString(), trimmed)
                    paragraph.clear()
                    paragraph.append(joined)
                }
                index++
            }
        }
    }
    flushAll()
    return blocks
}

/**
 * 软换行合并：两侧只要有一侧是中文/全角字符就不补空格，其余按英文补一个空格。
 * 例：「…与 Web 版「加密同步」互通，」+「另一台设备…」→ 直接相连；
 *     「APK 体积 23.7 →」+「20.2 MB」→ 中间一个空格。
 */
internal fun softJoin(left: String, right: String): String {
    if (left.isEmpty()) return right
    if (right.isEmpty()) return left
    val last = left.last()
    val first = right.first()
    return if (isCjk(last) || isCjk(first)) left + right else "$left $right"
}

private fun isCjk(char: Char): Boolean {
    val code = char.code
    return code in 0x2E80..0x9FFF || // CJK 部首扩展 ~ CJK 统一表意文字
        code in 0x3000..0x303F || // CJK 标点
        code in 0xFF00..0xFFEF // 全角字符（，；：（）等）
}

/** 解析行内 Markdown，按出现顺序返回带样式的片段。 */
internal fun parseInlineMarkdown(source: String): List<MarkdownSpan> =
    mergeSpans(parseInline(source, MarkdownSpan("")))

private fun parseInline(source: String, base: MarkdownSpan): List<MarkdownSpan> {
    val spans = mutableListOf<MarkdownSpan>()
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            spans += base.copy(text = plain.toString())
            plain.clear()
        }
    }

    var index = 0
    while (index < source.length) {
        val char = source[index]
        when {
            // 反斜杠转义
            char == '\\' && index + 1 < source.length -> {
                plain.append(source[index + 1])
                index += 2
            }

            // 行内代码：内容原样，不再解析内部标记。
            char == '`' -> {
                val end = source.indexOf('`', index + 1)
                if (end > index + 1) {
                    flushPlain()
                    spans += base.copy(text = source.substring(index + 1, end), code = true)
                    index = end + 1
                } else {
                    plain.append(char)
                    index++
                }
            }

            // 删除线 ~~…~~
            char == '~' && index + 1 < source.length && source[index + 1] == '~' -> {
                val end = source.indexOf("~~", index + 2)
                if (end > index + 2) {
                    flushPlain()
                    spans += parseInline(source.substring(index + 2, end), base.copy(strike = true))
                    index = end + 2
                } else {
                    plain.append("~~")
                    index += 2
                }
            }

            // 粗体 **…** / __…__
            char == '*' && index + 1 < source.length && source[index + 1] == '*' -> {
                val end = source.indexOf("**", index + 2)
                if (end > index + 2) {
                    flushPlain()
                    spans += parseInline(source.substring(index + 2, end), base.copy(bold = true))
                    index = end + 2
                } else {
                    plain.append("**")
                    index += 2
                }
            }

            char == '_' && index + 1 < source.length && source[index + 1] == '_' -> {
                val end = source.indexOf("__", index + 2)
                if (end > index + 2) {
                    flushPlain()
                    spans += parseInline(source.substring(index + 2, end), base.copy(bold = true))
                    index = end + 2
                } else {
                    plain.append("__")
                    index += 2
                }
            }

            // 斜体 *…*（单星号）
            char == '*' -> {
                val end = source.indexOf('*', index + 1)
                if (end > index + 1 && !source[index + 1].isWhitespace()) {
                    flushPlain()
                    spans += parseInline(source.substring(index + 1, end), base.copy(italic = true))
                    index = end + 1
                } else {
                    plain.append(char)
                    index++
                }
            }

            // 斜体 _…_：只在下划线不在单词内部时生效（避免把 drive_ledger 拆开）。
            char == '_' -> {
                val previous = source.getOrNull(index - 1)
                val next = source.getOrNull(index + 1)
                val canOpen = (previous == null || !previous.isLetterOrDigit()) &&
                    next != null && !next.isWhitespace() && next != '_'
                if (canOpen) {
                    val end = source.indexOf('_', index + 1)
                    val after = source.getOrNull(end + 1)
                    if (end > index + 1 && (after == null || !after.isLetterOrDigit())) {
                        flushPlain()
                        spans += parseInline(source.substring(index + 1, end), base.copy(italic = true))
                        index = end + 1
                    } else {
                        plain.append(char)
                        index++
                    }
                } else {
                    plain.append(char)
                    index++
                }
            }

            // [文字](链接)
            char == '[' -> {
                val closeLabel = source.indexOf(']', index + 1)
                if (
                    closeLabel > index + 1 &&
                    closeLabel + 1 < source.length &&
                    source[closeLabel + 1] == '('
                ) {
                    val closeUrl = source.indexOf(')', closeLabel + 2)
                    if (closeUrl > closeLabel + 2) {
                        val label = source.substring(index + 1, closeLabel)
                        val url = source.substring(closeLabel + 2, closeUrl).trim()
                        if (url.isNotEmpty()) {
                            flushPlain()
                            spans += parseInline(label, base.copy(url = url))
                            index = closeUrl + 1
                        } else {
                            plain.append(char)
                            index++
                        }
                    } else {
                        plain.append(char)
                        index++
                    }
                } else {
                    plain.append(char)
                    index++
                }
            }

            // <https://…> / <mailto:…> 自动链接
            char == '<' -> {
                val close = source.indexOf('>', index + 1)
                if (close > index + 1) {
                    val inner = source.substring(index + 1, close)
                    if (
                        inner.startsWith("https://") ||
                        inner.startsWith("http://") ||
                        inner.startsWith("mailto:")
                    ) {
                        flushPlain()
                        spans += base.copy(
                            text = inner.removePrefix("mailto:"),
                            url = inner,
                        )
                        index = close + 1
                    } else {
                        plain.append(char)
                        index++
                    }
                } else {
                    plain.append(char)
                    index++
                }
            }

            else -> {
                plain.append(char)
                index++
            }
        }
    }
    flushPlain()
    return spans
}

/** 合并相邻且样式完全相同的片段，减少 AnnotatedString 的 range 数量。 */
private fun mergeSpans(spans: List<MarkdownSpan>): List<MarkdownSpan> {
    val merged = mutableListOf<MarkdownSpan>()
    spans.forEach { span ->
        if (span.text.isEmpty()) return@forEach
        val last = merged.lastOrNull()
        if (
            last != null &&
            last.bold == span.bold &&
            last.italic == span.italic &&
            last.strike == span.strike &&
            last.code == span.code &&
            last.url == span.url
        ) {
            merged[merged.lastIndex] = last.copy(text = last.text + span.text)
        } else {
            merged += span
        }
    }
    return merged
}
