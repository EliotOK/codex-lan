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
@Composable private fun inline(node: Node): AnnotatedString = themedInline(node, MaterialTheme.colorScheme)
private fun themedInline(node:Node, colors:androidx.compose.material3.ColorScheme):AnnotatedString = buildAnnotatedString {
    fun visit(n: Node) {
        when(n) {
            is org.commonmark.node.Text -> append(n.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant)) { append(n.literal) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children(n).forEach(::visit) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(n).forEach(::visit) }
            is SoftLineBreak -> append(" ")
            is HardLineBreak -> append("\n")
            is HtmlInline -> append(n.literal)
            is Link -> {
                if (Regex("https?://.+", RegexOption.IGNORE_CASE).matches(n.destination)) {
                    withLink(LinkAnnotation.Url(n.destination, TextLinkStyles(style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)))) { children(n).forEach(::visit) }
                } else children(n).forEach(::visit)
            }
            is Image -> Unit
            else -> children(n).forEach(::visit)
        }
    }
    children(node).forEach(::visit)
}
@Composable fun MarkdownMessage(text: String, loader: suspend (ChatImage,Boolean)->android.graphics.Bitmap) {
    val document = remember(text) { markdownParser.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { children(document).forEach { MarkdownBlock(it,loader) } }
}
private fun images(node:Node):List<Image> = if(node is Image)listOf(node)else children(node).flatMap(::images)
private fun Image.altText():String = children(this).joinToString("") { if(it is org.commonmark.node.Text)it.literal else "" }.ifBlank { "图片" }
@Composable private fun InlineContent(node:Node,loader:suspend(ChatImage,Boolean)->android.graphics.Bitmap,fontSize:androidx.compose.ui.unit.TextUnit=14.sp,fontWeight:FontWeight=FontWeight.Normal) {
    val text=inline(node)
    if(text.text.isNotBlank())Text(text,fontSize=fontSize,lineHeight=(fontSize.value*1.6).sp,fontWeight=fontWeight)
    images(node).forEach{image->ImagePreview(ChatImage(image.destination,image.altText()),loader)}
}
@Composable private fun MarkdownBlock(node: Node,loader:suspend(ChatImage,Boolean)->android.graphics.Bitmap) {
    when(node) {
        is Heading -> InlineContent(node,loader,fontWeight = FontWeight.Bold, fontSize = when(node.level){1->23.sp;2->20.sp;else->17.sp})
        is org.commonmark.node.Paragraph -> InlineContent(node,loader)
        is FencedCodeBlock -> CodeBlock(node.literal)
        is IndentedCodeBlock -> CodeBlock(node.literal)
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var index = if (node is OrderedList) node.startNumber else 1
            children(node).forEach { item -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if(node is OrderedList) "${index++}." else "•", modifier = Modifier.widthIn(min = 16.dp), fontSize = 14.sp)
                Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(6.dp)){children(item).forEach{MarkdownBlock(it,loader)}}
            } }
        }
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
            Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(8.dp)){children(node).forEach{MarkdownBlock(it,loader)}}
        }
        is ThematicBreak -> HorizontalDivider()
        is HtmlBlock -> Text(node.literal, fontSize = 14.sp, lineHeight = 23.sp)
        is TableBlock -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            children(node).flatMap(::children).forEach { row -> Row {
                children(row).forEach { cell -> Text(inline(cell), fontSize = 12.sp, lineHeight = 20.sp,
                    fontWeight = if(row.parent is TableHead) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(145.dp).background(if(row.parent is TableHead) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface).padding(10.dp)) }
            }; HorizontalDivider() }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { children(node).forEach{MarkdownBlock(it,loader)} }
    }
}
@Composable private fun CodeBlock(text: String) {
    Text(text.trimEnd('\n'), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant,MaterialTheme.shapes.small).horizontalScroll(rememberScrollState()).padding(12.dp))
}
