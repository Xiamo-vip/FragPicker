package com.fragpicker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.theme.*
import com.fragpicker.android.core.auth.*
import com.fragpicker.android.feature.auth.*

class MainActivity : ComponentActivity() {
    private val theme: ThemeViewModel by viewModels {
        viewModelFactory { initializer { ThemeViewModel(ThemeRepository(applicationContext)) } }
    }
    private val authRepository by lazy { AuthRepository(AuthApi(), SessionVault(applicationContext)) }
    private val login: LoginViewModel by viewModels {
        viewModelFactory { initializer { LoginViewModel(authRepository) } }
    }
    private val registration: RegistrationViewModel by viewModels {
        viewModelFactory { initializer { RegistrationViewModel(authRepository) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by theme.mode.collectAsStateWithLifecycle()
            val account by login.state.collectAsStateWithLifecycle()
            val registrationState by registration.state.collectAsStateWithLifecycle()
            var showRegistration by rememberSaveable { mutableStateOf(false) }
            var registeredUsername by rememberSaveable { mutableStateOf("") }
            BackHandler(enabled = showRegistration) { showRegistration = false }
            FragmentsPickerTheme(mode) {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                            .padding(horizontal = 28.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Text("FragmentsPicker", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        val user = account.user
                        if (user == null) {
                            if (showRegistration) {
                                RegistrationScreen(registrationState, registration::register,
                                    onBack = { showRegistration = false }, onCreated = {
                                        registeredUsername = it; login.clearError(); showRegistration = false
                                    })
                            } else {
                                LoginScreen(account, login::login, onRegister = {
                                    registration.reset(); showRegistration = true
                                }, initialUsername = registeredUsername)
                            }
                        } else {
                            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("欢迎回来，${user.username}", style = MaterialTheme.typography.headlineSmall)
                                    Text("已登录你的个人知识空间", style = MaterialTheme.typography.bodyLarge)
                                    Text("每日回顾时区：${user.businessZone}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
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
