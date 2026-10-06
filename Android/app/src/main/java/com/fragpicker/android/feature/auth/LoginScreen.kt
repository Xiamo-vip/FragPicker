package com.fragpicker.android.feature.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(state: LoginUiState, onLogin: (String, String) -> Unit, onRegister: () -> Unit, initialUsername: String = "") {
    var username by rememberSaveable(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        onLogin(username.trim(), password)
        password = ""
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(40.dp))
            Text("零散灵感，值得被记住。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("登录你的个人知识空间", style = MaterialTheme.typography.bodyLarge)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (initialUsername.isNotEmpty()) Text("账号已创建，请登录。", color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(username, onValueChange = { if (it.length <= 32) username = it },
            label = { Text("用户名") }, singleLine = true, enabled = !state.loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("login_username"))
        OutlinedTextField(password, onValueChange = { if (it.length <= 64) password = it },
            label = { Text("密码") }, singleLine = true, enabled = !state.loading,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏密码" else "显示密码")
            } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!state.loading) submit() }),
            modifier = Modifier.fillMaxWidth().testTag("login_password"))
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("login_error"))
        }
        Button(onClick = submit, enabled = !state.loading && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login_submit")) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("正在连接…")
            } else Text("登录")
        }
        TextButton(onClick = onRegister, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
            Text("创建账号")
        }
    }
}
