package com.forge.os.presentation.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.theme.LocalForgePalette

private val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.textPrimary
private val Orange: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.orange

/**
 * Renders a Markdown string inside a chat bubble.
 * Supports: # headers, **bold**, *italic*, `code`, ```code blocks```,
 * > blockquotes, - bullet lists, and numbered lists.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseColor: Color = TextPrimary,
    baseFontSize: Float = 13f
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val segments = parseMarkdownSegments(text)
        for (seg in segments) {
            when (seg) {
                is MdSegment.CodeBlock -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0d1117), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            seg.code,
                            color = Color(0xFF79c0ff),
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseFontSize - 1).sp,
                            lineHeight = (baseFontSize + 4).sp
                        )
                    }
                }
                is MdSegment.Blockquote -> {
                    Row {
                        Box(Modifier.width(3.dp).height(IntrinsicSize.Min).background(Orange))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            buildInlineAnnotated(seg.content, baseFontSize, baseColor),
                            color = Color(0xFFa0a0a0),
                            fontFamily = FontFamily.Monospace,
                            fontSize = baseFontSize.sp,
                            lineHeight = (baseFontSize + 5).sp,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
                is MdSegment.Header -> {
                    val size = when (seg.level) {
                        1 -> baseFontSize + 6
                        2 -> baseFontSize + 4
                        3 -> baseFontSize + 2
                        else -> baseFontSize + 1
                    }
                    Text(
                        seg.content,
                        color = Orange,
                        fontFamily = FontFamily.Monospace,
                        fontSize = size.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (size + 6).sp
                    )
                }
                is MdSegment.BulletItem -> {
                    val context = LocalContext.current
                    val annotated = buildInlineAnnotated(seg.content, baseFontSize, baseColor)
                    Row {
                        Text("• ", color = Orange, fontFamily = FontFamily.Monospace, fontSize = baseFontSize.sp)
                        ClickableText(
                            text = annotated,
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = baseFontSize.sp,
                                lineHeight = (baseFontSize + 5).sp,
                            ),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { ann ->
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(ann.item))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    }
                            }
                        )
                    }
                }
                is MdSegment.NumberedItem -> {
                    val context = LocalContext.current
                    val annotated = buildInlineAnnotated(seg.content, baseFontSize, baseColor)
                    Row {
                        Text("${seg.number}. ", color = Orange, fontFamily = FontFamily.Monospace, fontSize = baseFontSize.sp)
                        ClickableText(
                            text = annotated,
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = baseFontSize.sp,
                                lineHeight = (baseFontSize + 5).sp,
                            ),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { ann ->
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(ann.item))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    }
                            }
                        )
                    }
                }
                is MdSegment.HorizontalRule -> {
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(Color(0xFF333333))
                    )
                }
                is MdSegment.Table -> {
                    MarkdownTable(seg, baseFontSize, baseColor)
                }
                is MdSegment.Paragraph -> {
                    if (seg.content.isNotBlank()) {
                        val context = LocalContext.current
                        val annotated = buildInlineAnnotated(seg.content, baseFontSize, baseColor)
                        ClickableText(
                            text = annotated,
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = baseFontSize.sp,
                                lineHeight = (baseFontSize + 5).sp,
                            ),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { ann ->
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(ann.item))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    }
                            }
                        )
                    }
                }
            }
        }
    }
}

sealed class MdSegment {
    data class Header(val level: Int, val content: String) : MdSegment()
    data class CodeBlock(val lang: String, val code: String) : MdSegment()
    data class Blockquote(val content: String) : MdSegment()
    data class BulletItem(val content: String) : MdSegment()
    data class NumberedItem(val number: Int, val content: String) : MdSegment()
    data class Paragraph(val content: String) : MdSegment()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdSegment()
    object HorizontalRule : MdSegment()
}

@Composable
private fun MarkdownTable(table: MdSegment.Table, baseFontSize: Float, baseColor: Color) {
    val palette = LocalForgePalette.current
    val colCount = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return

    // Estimate column widths based on content length, min 60dp max 200dp
    val colWidths = (0 until colCount).map { col ->
        val maxLen = maxOf(
            table.headers.getOrElse(col) { "" }.length,
            table.rows.maxOfOrNull { it.getOrElse(col) { "" }.length } ?: 0
        )
        (maxLen * 8 + 16).coerceIn(60, 200).dp
    }

    val borderColor = Color(0xFF333333)
    val headerBg   = Color(0xFF1a1a2e)
    val rowBg      = Color(0xFF0f0f1a)
    val altRowBg   = Color(0xFF141420)

    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
    ) {
        Column {
            // Header row
            Row(Modifier.background(headerBg)) {
                colWidths.forEachIndexed { col, width ->
                    Box(
                        Modifier
                            .width(width)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = table.headers.getOrElse(col) { "" },
                            color = palette.orange,
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseFontSize - 1).sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (col < colCount - 1) {
                        Box(Modifier.width(1.dp).height(IntrinsicSize.Max).background(borderColor))
                    }
                }
            }
            // Separator
            Box(Modifier.fillMaxWidth().height(1.dp).background(borderColor))
            // Data rows
            table.rows.forEachIndexed { rowIdx, row ->
                val bg = if (rowIdx % 2 == 0) rowBg else altRowBg
                Row(Modifier.background(bg)) {
                    colWidths.forEachIndexed { col, width ->
                        Box(
                            Modifier
                                .width(width)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = row.getOrElse(col) { "" },
                                color = baseColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = (baseFontSize - 1).sp,
                                lineHeight = (baseFontSize + 3).sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (col < colCount - 1) {
                            Box(Modifier.width(1.dp).height(IntrinsicSize.Max).background(borderColor))
                        }
                    }
                }
                if (rowIdx < table.rows.size - 1) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(borderColor))
                }
            }
        }
    }
}

fun parseMarkdownSegments(text: String): List<MdSegment> {
    val result = mutableListOf<MdSegment>()
    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // Fenced code block
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            result.add(MdSegment.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }
        // Header
        val headerMatch = Regex("^(#{1,4})\\s+(.+)").find(line)
        if (headerMatch != null) {
            result.add(MdSegment.Header(
                level = headerMatch.groupValues[1].length,
                content = headerMatch.groupValues[2]
            ))
            i++; continue
        }
        // Horizontal rule
        if (line.trim().matches(Regex("[-*_]{3,}"))) {
            result.add(MdSegment.HorizontalRule)
            i++; continue
        }
        // Blockquote
        if (line.startsWith("> ") || line.startsWith(">")) {
            val bqContent = line.removePrefix("> ").removePrefix(">")
            result.add(MdSegment.Blockquote(bqContent))
            i++; continue
        }
        // Bullet list
        val bulletMatch = Regex("^\\s*[-*+]\\s+(.+)").find(line)
        if (bulletMatch != null) {
            result.add(MdSegment.BulletItem(bulletMatch.groupValues[1]))
            i++; continue
        }
        // Numbered list
        val numberedMatch = Regex("^\\s*(\\d+)\\.\\s+(.+)").find(line)
        if (numberedMatch != null) {
            result.add(MdSegment.NumberedItem(
                number = numberedMatch.groupValues[1].toIntOrNull() ?: 1,
                content = numberedMatch.groupValues[2]
            ))
            i++; continue
        }
        // Table — a run of lines containing '|', with a separator row (---|---) as line 2
        if (line.contains("|")) {
            val tableLines = mutableListOf<String>()
            var j = i
            while (j < lines.size && lines[j].contains("|")) {
                tableLines.add(lines[j])
                j++
            }
            if (tableLines.size >= 2) {
                val isSeparator = { s: String -> s.replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty() }
                val headerLine = tableLines[0]
                val sepIdx = tableLines.indexOfFirst { isSeparator(it) }
                if (sepIdx == 1 && tableLines.size >= 2) {
                    val parseRow = { row: String ->
                        row.trim().trimStart('|').trimEnd('|')
                            .split("|").map { it.trim() }
                    }
                    val headers = parseRow(headerLine)
                    val rows = tableLines.drop(2).map { parseRow(it) }
                    result.add(MdSegment.Table(headers, rows))
                    i = j
                    continue
                }
            }
        }
        // Regular paragraph
        result.add(MdSegment.Paragraph(line))
        i++
    }
    return result
}

fun buildInlineAnnotated(text: String, baseFontSize: Float, baseColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var remaining = text
        while (remaining.isNotEmpty()) {
            // Markdown link [label](url)
            val mdLinkMatch = Regex("\\[([^]]+)]\\(([^)]+)\\)").find(remaining)
            // Raw URL https?://...
            val urlMatch = Regex("https?://[^\\s,)>\"']+").find(remaining)

            // Bold+italic ***text***
            val boldItalicIdx = remaining.indexOf("***")
            // Bold **text**
            val boldIdx = remaining.indexOf("**")
            // Italic *text*
            val italicIdx = remaining.indexOf("*").let { idx ->
                if (idx >= 0 && remaining.getOrNull(idx + 1) == '*') -1 else idx
            }
            // Inline code `text`
            val codeIdx = remaining.indexOf("`")
            // Strikethrough ~~text~~
            val strikeIdx = remaining.indexOf("~~")

            // Collect all candidates with their start positions
            val candidates = mutableListOf<Pair<Int, String>>()
            if (boldItalicIdx >= 0) candidates += boldItalicIdx to "***"
            if (boldIdx >= 0 && (boldItalicIdx < 0 || boldIdx < boldItalicIdx)) candidates += boldIdx to "**"
            if (italicIdx >= 0) candidates += italicIdx to "*"
            if (codeIdx >= 0) candidates += codeIdx to "`"
            if (strikeIdx >= 0) candidates += strikeIdx to "~~"
            if (mdLinkMatch != null) candidates += mdLinkMatch.range.first to "mdlink"
            if (urlMatch != null) candidates += urlMatch.range.first to "url"

            val firstIdx = candidates.minByOrNull { it.first }

            if (firstIdx == null) {
                withStyle(SpanStyle(color = baseColor)) { append(remaining) }
                break
            }

            val (markerStart, marker) = firstIdx

            // Append plain text before the marker
            if (markerStart > 0) {
                withStyle(SpanStyle(color = baseColor)) { append(remaining.substring(0, markerStart)) }
            }

            when (marker) {
                "mdlink" -> {
                    val m = mdLinkMatch!!
                    val label = m.groupValues[1]
                    val url   = m.groupValues[2]
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(
                        color = Color(0xFF60A5FA),
                        textDecoration = TextDecoration.Underline,
                    )) { append(label) }
                    pop()
                    remaining = remaining.substring(m.range.last + 1)
                }
                "url" -> {
                    val m = urlMatch!!
                    val url = m.value
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(
                        color = Color(0xFF60A5FA),
                        textDecoration = TextDecoration.Underline,
                    )) { append(url) }
                    pop()
                    remaining = remaining.substring(m.range.last + 1)
                }
                else -> {
                    val afterMarker = remaining.substring(markerStart + marker.length)
                    val endIdx = afterMarker.indexOf(marker)
                    if (endIdx < 0) {
                        withStyle(SpanStyle(color = baseColor)) { append(remaining.substring(markerStart)) }
                        break
                    }
                    val inner = afterMarker.substring(0, endIdx)
                    when (marker) {
                        "***" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = baseColor)) { append(inner) }
                        "**"  -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) { append(inner) }
                        "*"   -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) { append(inner) }
                        "`"   -> withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0xFF1e1e2e),
                            color = Color(0xFF89dceb),
                            fontSize = (baseFontSize - 1).sp,
                        )) { append(inner) }
                        "~~"  -> withStyle(SpanStyle(color = Color(0xFF666666), textDecoration = TextDecoration.LineThrough)) { append(inner) }
                    }
                    remaining = afterMarker.substring(endIdx + marker.length)
                }
            }
        }
    }
}
