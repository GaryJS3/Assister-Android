package net.thegary.assister.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.node.Paragraph
import org.commonmark.node.Text as LiteralText
import org.commonmark.parser.Parser

internal val markdownParser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()

internal fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) { add(child); child = child.next }
}

/** Native Compose text; raw HTML stays text and images render their alt text. */
@Composable
internal fun MarkdownText(source: String, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val document = remember(source) { markdownParser.parse(source) }
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            document.children().forEach { MarkdownBlock(it, style) }
        }
    }
}

internal fun markdownInline(node: Node, linkColor: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    fun visit(value: Node) {
        fun children() { value.children().forEach { visit(it) } }
        when (value) {
            is LiteralText -> append(value.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(value.literal) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children() }
            is SoftLineBreak -> append(" ")
            is HardLineBreak -> append("\n")
            is HtmlInline -> append(value.literal)
            is Link -> {
                val scheme = value.destination.substringBefore(':', "").lowercase()
                if (scheme in setOf("https", "http", "mailto")) {
                    withLink(LinkAnnotation.Url(value.destination, TextLinkStyles(SpanStyle(
                        color = linkColor, textDecoration = TextDecoration.Underline)))) { children() }
                } else children()
            }
            else -> children()
        }
    }
    node.children().forEach { visit(it) }
}

@Composable
private fun MarkdownBlock(node: Node, style: TextStyle) {
    val linkColor = MaterialTheme.colorScheme.primary
    when (node) {
        is Paragraph -> Text(markdownInline(node, linkColor), style = style)
        is Heading -> Text(markdownInline(node, linkColor), style = when (node.level) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        }, fontWeight = FontWeight.Bold)
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.children().forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val marker = if (node is OrderedList) "${(node.markerStartNumber ?: 1) + index}." else "•"
                    Text(marker, Modifier.width(28.dp), style = style)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.children().forEach { MarkdownBlock(it, style) }
                    }
                }
            }
        }
        is BlockQuote -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                node.children().forEach { MarkdownBlock(it, style) }
            }
        }
        is FencedCodeBlock -> MarkdownCode(node.literal, style)
        is IndentedCodeBlock -> MarkdownCode(node.literal, style)
        is ThematicBreak -> HorizontalDivider()
        is TableBlock -> MarkdownTable(node, style)
        is HtmlBlock -> Text(node.literal, style = style)
        else -> node.children().forEach { MarkdownBlock(it, style) }
    }
}

@Composable
private fun MarkdownCode(code: String, style: TextStyle) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text(code.trimEnd('\n'), style = style, fontFamily = FontFamily.Monospace, softWrap = false)
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock, style: TextStyle) {
    val rows = remember(table) { table.children().flatMap { it.children() }.map { row -> row.children().filterIsInstance<TableCell>() } }
    val widths = remember(table) {
        (0 until (rows.maxOfOrNull { it.size } ?: 0)).map { column ->
            ((rows.maxOfOrNull { row -> row.getOrNull(column)?.let { markdownInline(it).length } ?: 0 } ?: 0) * 8 + 24)
                .coerceIn(100, 240).dp
        }
    }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        rows.forEach { cells ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                cells.forEachIndexed { index, cell ->
                    Box(Modifier.width(widths[index]).fillMaxHeight()
                        .background(if (cell.isHeader) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(8.dp)) {
                        Text(markdownInline(cell, MaterialTheme.colorScheme.primary), Modifier.fillMaxWidth(), style = style,
                            fontWeight = if (cell.isHeader) FontWeight.Bold else null,
                            textAlign = when (cell.alignment) {
                                TableCell.Alignment.CENTER -> TextAlign.Center
                                TableCell.Alignment.RIGHT -> TextAlign.End
                                else -> TextAlign.Start
                            })
                    }
                }
            }
        }
    }
}
