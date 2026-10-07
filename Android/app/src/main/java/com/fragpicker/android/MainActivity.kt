package com.fragpicker.android

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fragpicker.android.core.theme.*
import com.fragpicker.android.core.auth.*
import com.fragpicker.android.feature.auth.*
import com.fragpicker.android.feature.settings.SettingsScreen
import com.fragpicker.android.core.ui.AppShell
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.feature.feed.FeedRoute
import com.fragpicker.android.feature.feed.IncomingShareViewModel
import com.fragpicker.android.feature.detail.DetailRoute
import com.fragpicker.android.core.ui.LocalOpenFragment
import com.fragpicker.android.feature.history.HistoryRoute
import com.fragpicker.android.feature.chat.ChatRoute

class MainActivity : ComponentActivity() {
    private val shares: IncomingShareViewModel by viewModels()
    private val theme: ThemeViewModel by viewModels {
        viewModelFactory { initializer { ThemeViewModel(ThemeRepository(applicationContext)) } }
    }
    private val authRepository get() = (application as FragPickerApplication).authRepository
    private val login: LoginViewModel by viewModels {
        viewModelFactory { initializer { LoginViewModel(authRepository) } }
    }
    private val registration: RegistrationViewModel by viewModels {
        viewModelFactory { initializer { RegistrationViewModel(authRepository) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) shares.accept(intent)
        enableEdgeToEdge()
        setContent {
            val mode by theme.mode.collectAsStateWithLifecycle()
            val account by login.state.collectAsStateWithLifecycle()
            val registrationState by registration.state.collectAsStateWithLifecycle()
            val incomingShare by shares.pending.collectAsStateWithLifecycle()
            var showRegistration by rememberSaveable { mutableStateOf(false) }
            var registeredUsername by rememberSaveable { mutableStateOf("") }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = showRegistration || showSettings) { showRegistration = false; showSettings = false }
            FragmentsPickerTheme(mode) {
                if (account.user != null) {
                    AppShell(account.user!!,
                        openFeedRequest = incomingShare?.getString("id"),
                        detail = { id, back -> DetailRoute(JsonApi(authRepository, account.user!!.id), id, back) },
                        history = { HistoryRoute(JsonApi(authRepository, account.user!!.id), LocalOpenFragment.current) },
                        chat = { ChatRoute(JsonApi(authRepository, account.user!!.id), LocalOpenFragment.current) },
                        feed = { FeedRoute(JsonApi(authRepository, account.user!!.id), account.user!!, LocalOpenFragment.current,
                            incomingShare?.getString("id"), incomingShare?.getString("text"), shares::consume) }, settings = {
                        SettingsScreen(mode, theme::setMode, account, onLogout = {
                            registeredUsername = ""; showRegistration = false; showSettings = false; shares.clear(); login.logout()
                        })
                    })
                } else if (showSettings) {
                    SettingsScreen(mode, theme::setMode, account, onLogout = {
                        registeredUsername = ""; showRegistration = false; showSettings = false; login.logout()
                    }, onBack = { showSettings = false })
                } else {
                Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                            .padding(horizontal = 28.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("FragmentsPicker", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, "设置") }
                        }
                        if (incomingShare != null) Text("已接收视频分享，登录后可确认投喂。", color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("incoming_share"))
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
                        Text("慢一点，记住多一点。", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
                    }
                }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); shares.accept(intent)
    }
}
