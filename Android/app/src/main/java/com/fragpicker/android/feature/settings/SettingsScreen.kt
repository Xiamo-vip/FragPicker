package com.fragpicker.android.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fragpicker.android.BuildConfig
import com.fragpicker.android.core.theme.ThemeMode
import com.fragpicker.android.feature.auth.LoginUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(mode: ThemeMode, onMode: (ThemeMode) -> Unit, account: LoginUiState,
                   onLogout: () -> Unit, onBack: (() -> Unit)? = null) {
    var confirmLogout by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("设置") }, navigationIcon = {
            onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("让知识空间，更像你", style = MaterialTheme.typography.headlineSmall)
            Text("外观", style = MaterialTheme.typography.titleMedium)
            ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Palette, null, tint = MaterialTheme.colorScheme.primary)
                        Text("主题样式", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    ThemeMode.entries.forEach { option ->
                        FilterChip(selected = mode == option, onClick = { onMode(option) },
                            label = { Text(option.label) }, modifier = Modifier.fillMaxWidth(),
                            leadingIcon = if (mode == option) { { Icon(Icons.Rounded.Check, null) } } else null)
                    }
                    Text("外观选择会保存在此设备，跟随系统可自动切换。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            account.user?.let { user ->
                Text("账号", style = MaterialTheme.typography.titleMedium)
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(user.username, style = MaterialTheme.typography.titleLarge)
                        Text("每日回顾 · ${user.businessZone}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                account.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { confirmLogout = true }, enabled = !account.loading,
                    modifier = Modifier.fillMaxWidth()) { Text(if (account.loading) "正在退出…" else "退出登录") }
            }
            HorizontalDivider()
            Text("FragmentsPicker", style = MaterialTheme.typography.titleMedium)
            Text("版本 ${BuildConfig.VERSION_NAME} · 慢一点，记住多一点。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(100.dp))
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
        title = { Text("退出知识空间？") }, text = { Text("此账号在所有设备上的会话都会撤销，已保存的内容会保留。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("确认退出") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}
