package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染（task 10-28-update-dialog-markdown）。
 *
 * 为什么自己写而不引三方库：更新弹窗只需要「发布说明」这一种场景，支持的语法子集
 * （标题 / 段落 / 无序与有序列表 / 代码块 / 引用 / 分隔线 / 粗体 / 斜体 / 行内代码 /
 * 链接 / 删除线）已覆盖 GitHub、Gitee 发行说明的常见写法；引一个完整的 Markdown 渲染库
 * （markdown-renderer / commonmark）会多出图片加载、主题桥接等一整套依赖与包体成本。
 *
 * 设计要点：
 * - 解析分两层：先按行切成 block（`parseMarkdownBlocks`），再对每个 block 做行内样式
 *   （`appendInlineMarkdown`，支持粗体内嵌斜体/行内代码，用 `withStyle` 压栈实现）；
 * - 链接用 `LinkAnnotation.Url`，由 Compose 的 Text 自动处理点击（无需 ClickableText）；
 * - 纯文本（没有 Markdown 标记）与原来一样按段落渲染，不会因为「解析」而变形。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 20.sp,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    val headingColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = buildAnnotatedString {
                        appendInlineMarkdown(block.text, codeBackground, linkColor)
                    },
                    fontSize = when (block.level) {
                        1 -> 18.sp
                        2 -> 16.sp
                        3 -> 15.sp
                        else -> 14.sp
                    },
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.SemiBold,
                    color = headingColor,
                )

                is MarkdownBlock.Paragraph -> Text(
                    text = buildAnnotatedString {
                        appendInlineMarkdown(block.text, codeBackground, linkColor)
                    },
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = color,
                )

                is MarkdownBlock.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width((block.depth * 14).dp))
                    Text(
                        text = if (block.ordered) "${block.number}." else "•",
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = color,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = buildAnnotatedString {
                            appendInlineMarkdown(block.text, codeBackground, linkColor)
                        },
                        modifier = Modifier.weight(1f),
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = color,
                    )
                }

                is MarkdownBlock.Code -> Surface(
                    color = codeBackground,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = block.code,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is MarkdownBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "▏",
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = buildAnnotatedString {
                            appendInlineMarkdown(block.text, codeBackground, linkColor)
                        },
                        modifier = Modifier.weight(1f),
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        fontStyle = FontStyle.Italic,
                        color = color,
                    )
                }

                MarkdownBlock.Rule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/* ================================ 块级解析 ================================ */

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(
        val depth: Int,
        val ordered: Boolean,
        val number: Int,
        val text: String,
    ) : MarkdownBlock

    data class Code(val code: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)([-*+]|(\\d{1,3})[.)])\\s+(.*)$")
private val ruleRegex = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks += MarkdownBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
    }

    var index = 0
    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trimEnd()
        val trimmed = line.trimStart()

        when {
            // 代码块（```）：原样保留内部文本与换行，不做行内解析
            trimmed.startsWith("```") -> {
                flushParagraph()
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    code.appendLine(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks += MarkdownBlock.Code(code.toString().trimEnd())
            }

            line.isBlank() -> {
                flushParagraph()
                index++
            }

            headingRegex.matches(line) -> {
                flushParagraph()
                val match = headingRegex.matchEntire(line)!!
                blocks += MarkdownBlock.Heading(
                    level = match.groupValues[1].length,
                    text = match.groupValues[2].trim(),
                )
                index++
            }

            ruleRegex.matches(line) -> {
                flushParagraph()
                blocks += MarkdownBlock.Rule
                index++
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(trimmed.removePrefix(">").trim())
                index++
            }

            bulletRegex.matches(line) -> {
                flushParagraph()
                val match = bulletRegex.matchEntire(line)!!
                val indent = match.groupValues[1].length
                val marker = match.groupValues[2]
                val ordered = marker.first().isDigit()
                blocks += MarkdownBlock.Bullet(
                    depth = (indent / 2).coerceAtMost(3),
                    ordered = ordered,
                    number = match.groupValues[3].toIntOrNull() ?: 1,
                    text = match.groupValues[4].trim(),
                )
                index++
            }

            // 普通段落：连续行用空格拼接（Markdown 软换行语义）
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
                index++
            }
        }
    }
    flushParagraph()
    return blocks
}

/* ================================ 行内解析 ================================ */

/**
 * 行内样式：`**粗体**` / `__粗体__`、`*斜体*` / `_斜体_`、`~~删除线~~`、
 * `` `行内代码` ``、`[文字](链接)`。
 *
 * 用递归 + `withStyle`/`withLink` 压栈处理嵌套（如 `**粗体里的 `代码`**`）；
 * 找不到闭合标记时按普通字符输出，不会吞掉原文。
 */
private fun AnnotatedString.Builder.appendInlineMarkdown(
    text: String,
    codeBackground: Color,
    linkColor: Color,
) {
    var index = 0
    while (index < text.length) {
        val rest = text.substring(index)
        when {
            rest.startsWith("`") -> {
                val end = text.indexOf('`', index + 1)
                if (end > index + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append('`')
                    index++
                }
            }

            rest.startsWith("**") || rest.startsWith("__") -> {
                val marker = rest.take(2)
                val end = text.indexOf(marker, index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineMarkdown(text.substring(index + 2, end), codeBackground, linkColor)
                    }
                    index = end + 2
                } else {
                    append(rest[0])
                    index++
                }
            }

            rest.startsWith("~~") -> {
                val end = text.indexOf("~~", index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendInlineMarkdown(text.substring(index + 2, end), codeBackground, linkColor)
                    }
                    index = end + 2
                } else {
                    append(rest[0])
                    index++
                }
            }

            rest.startsWith("*") || rest.startsWith("_") -> {
                val marker = rest[0]
                val end = text.indexOf(marker, index + 1)
                if (end > index + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInlineMarkdown(text.substring(index + 1, end), codeBackground, linkColor)
                    }
                    index = end + 1
                } else {
                    append(marker)
                    index++
                }
            }

            rest.startsWith("[") -> {
                val closeBracket = text.indexOf(']', index + 1)
                val openParen = if (closeBracket > 0) text.indexOf('(', closeBracket) else -1
                val closeParen = if (openParen == closeBracket + 1) text.indexOf(')', openParen) else -1
                if (closeBracket > 0 && openParen == closeBracket + 1 && closeParen > openParen) {
                    val label = text.substring(index + 1, closeBracket)
                    val url = text.substring(openParen + 1, closeParen).trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ),
                        ) {
                            appendInlineMarkdown(label, codeBackground, linkColor)
                        }
                        index = closeParen + 1
                    } else {
                        append(rest[0])
                        index++
                    }
                } else {
                    append(rest[0])
                    index++
                }
            }

            else -> {
                append(rest[0])
                index++
            }
        }
    }
}
