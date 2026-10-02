package local.codex.lan

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag

data class Appearance(val theme: String = "remote", val fontSize: Int = 16) {
    companion object {
        val themes = linkedMapOf("remote" to "经典黑", "discord" to "午夜紫", "mint" to "薄荷绿", "ocean" to "海蓝", "light" to "明亮")
        fun normalized(theme: String, size: Int) = Appearance(theme.takeIf { it in themes } ?: "remote", size.coerceIn(14, 22))
    }
}
class AppearanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun read() = Appearance.normalized(prefs.getString("theme", "remote").orEmpty(), prefs.getInt("fontSize", 16))
    fun save(value: Appearance) { prefs.edit().putString("theme", value.theme).putInt("fontSize", value.fontSize).apply() }
}
val LocalAppearance = staticCompositionLocalOf { Appearance() }
fun Appearance.colors(): ColorScheme = when (theme) {
    "remote" -> darkColorScheme(primary=Color.White,onPrimary=Color.Black,background=Color.Black,surface=Color(0xFF292929),surfaceVariant=Color(0xFF303030),onBackground=Color(0xFFF4F4F4),onSurface=Color(0xFFF4F4F4),onSurfaceVariant=Color(0xFFAAAAAA))
    "light" -> lightColorScheme(primary=Color(0xFF4752C4), background=Color(0xFFF5F6FA), surface=Color.White,
        surfaceVariant=Color(0xFFE8EAF2), onBackground=Color(0xFF22252E), onSurface=Color(0xFF22252E), onSurfaceVariant=Color(0xFF515766))
    "mint" -> darkColorScheme(primary=Color(0xFFAEF3CC), onPrimary=Color(0xFF102219), background=Color(0xFF111614), surface=Color(0xFF1D2521), surfaceVariant=Color(0xFF29332D), onSurfaceVariant=Color(0xFFABBEB1))
    "ocean" -> darkColorScheme(primary=Color(0xFF8FCCFF), onPrimary=Color(0xFF102436), background=Color(0xFF101A24), surface=Color(0xFF192735), surfaceVariant=Color(0xFF263A4B), onSurfaceVariant=Color(0xFFACBFD1))
    else -> darkColorScheme(primary=Color(0xFFB2B7FF), onPrimary=Color(0xFF252758), background=Color(0xFF24262B), surface=Color(0xFF2C2F35), surfaceVariant=Color(0xFF363941), onSurfaceVariant=Color(0xFFB5BAC5), onBackground=Color(0xFFF2F3F5), onSurface=Color(0xFFF2F3F5))
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppearancePanel(value: Appearance, onChange: (Appearance) -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("外观与字号", style=MaterialTheme.typography.titleLarge)
            Text("正文字号 · ${value.fontSize} sp", Modifier.testTag("font-size-label"))
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick={onChange(value.copy(fontSize=(value.fontSize-1).coerceAtLeast(14)))}, enabled=value.fontSize>14) { Text("缩小") }
                OutlinedButton(onClick={onChange(value.copy(fontSize=(value.fontSize+1).coerceAtMost(22)))}, enabled=value.fontSize<22) { Text("放大") }
                TextButton(onClick={onChange(value.copy(fontSize=16))}) { Text("默认字号") }
            }
            Text("主题颜色", style=MaterialTheme.typography.titleMedium)
            Appearance.themes.entries.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, label) -> FilterChip(selected=value.theme==key, onClick={onChange(value.copy(theme=key))}, label={Text(label)}) }
            } }
            Surface(shape=MaterialTheme.shapes.medium, color=MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(20.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Codex", color=MaterialTheme.colorScheme.primary)
                    Text("文字大小和主题会立即生效。\n设置保存在这台手机上。", fontSize=value.fontSize.sp, lineHeight=(value.fontSize*1.6).sp)
                }
            }
            Text("消息正文、代码与输入框使用所选字号，并继续跟随系统字体缩放。", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick=dismiss, modifier=Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}
