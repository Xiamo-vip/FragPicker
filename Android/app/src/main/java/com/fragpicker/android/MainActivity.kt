package com.fragpicker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.theme.*

class MainActivity : ComponentActivity() {
    private val theme: ThemeViewModel by viewModels {
        viewModelFactory { initializer { ThemeViewModel(ThemeRepository(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by theme.mode.collectAsStateWithLifecycle()
            FragmentsPickerTheme(mode) {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                            .padding(horizontal = 28.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Text("FragmentsPicker", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(48.dp))
                                Text("零散灵感，\n值得被记住。", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                                Text("把喜欢的内容，留成自己的知识。", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        Text("你的个人知识空间", style = MaterialTheme.typography.titleMedium)
                        Text("收集有价值的视频，在每天的回顾中梳理重点，也在需要时重新找回灵感。",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("外观", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ThemeMode.entries.forEach { option ->
                                FilterChip(selected = option == mode, onClick = { theme.setMode(option) }, label = { Text(option.label) })
                            }
                        }
                        Text("慢一点，记住多一点。", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
                    }
                }
            }
        }
    }
}
