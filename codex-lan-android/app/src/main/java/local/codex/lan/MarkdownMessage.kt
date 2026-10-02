package local.codex.lan

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.*

private val markdownParser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
private fun children(node: Node): List<Node> = buildList { var child = node.firstChild; while (child != null) { add(child); child = child.next } }
private fun inline(node: Node): AnnotatedString = buildAnnotatedString {
    fun visit(n: Node) {
        when(n) {
            is org.commonmark.node.Text -> append(n.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF304236))) { append(n.literal) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children(n).forEach(::visit) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(n).forEach(::visit) }
            is SoftLineBreak -> append(" ")
            is HardLineBreak -> append("\n")
            is HtmlInline -> append(n.literal)
            is Link -> {
                if (Regex("https?://.+", RegexOption.IGNORE_CASE).matches(n.destination)) {
                    withLink(LinkAnnotation.Url(n.destination, TextLinkStyles(style = SpanStyle(color = Color(0xFFAEF3CC), textDecoration = TextDecoration.Underline)))) { children(n).forEach(::visit) }
                } else children(n).forEach(::visit)
            }
            is Image -> { append("[图片："); children(n).forEach(::visit); append("]") }
            else -> children(n).forEach(::visit)
        }
    }
    children(node).forEach(::visit)
}
@Composable fun MarkdownMessage(text: String) {
    val document = remember(text) { markdownParser.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { children(document).forEach { MarkdownBlock(it) } }
}
@Composable private fun MarkdownBlock(node: Node) {
    when(node) {
        is Heading -> Text(inline(node), fontWeight = FontWeight.Bold, fontSize = when(node.level){1->23.sp;2->20.sp;else->17.sp}, lineHeight = 29.sp)
        is org.commonmark.node.Paragraph -> Text(inline(node), fontSize = 14.sp, lineHeight = 23.sp)
        is FencedCodeBlock -> CodeBlock(node.literal)
        is IndentedCodeBlock -> CodeBlock(node.literal)
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var index = if (node is OrderedList) node.startNumber else 1
            children(node).forEach { item -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if(node is OrderedList) "${index++}." else "•", modifier = Modifier.widthIn(min = 16.dp), fontSize = 14.sp)
                Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(6.dp)){children(item).forEach{MarkdownBlock(it)}}
            } }
        }
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Color(0xFF668E74)))
            Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(8.dp)){children(node).forEach{MarkdownBlock(it)}}
        }
        is ThematicBreak -> HorizontalDivider()
        is HtmlBlock -> Text(node.literal, fontSize = 14.sp, lineHeight = 23.sp)
        is TableBlock -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            children(node).flatMap(::children).forEach { row -> Row {
                children(row).forEach { cell -> Text(inline(cell), fontSize = 12.sp, lineHeight = 20.sp,
                    fontWeight = if(row.parent is TableHead) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(145.dp).background(if(row.parent is TableHead) Color(0xFF2B4032) else Color(0xFF16201A)).padding(10.dp)) }
            }; HorizontalDivider() }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { children(node).forEach{MarkdownBlock(it)} }
    }
}
@Composable private fun CodeBlock(text: String) {
    Text(text.trimEnd('\n'), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0C110E),MaterialTheme.shapes.small).horizontalScroll(rememberScrollState()).padding(12.dp))
}
