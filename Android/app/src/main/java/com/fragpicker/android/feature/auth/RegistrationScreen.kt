package com.fragpicker.android.feature.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun RegistrationScreen(state: RegistrationUiState, onRegister: (String, String, String) -> Boolean,
                       onBack: () -> Unit, onCreated: (String) -> Unit) {
    val created = state.createdUser
    if (created != null) {
        Text("账号已创建", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text("${created.username}，欢迎拥有自己的知识空间。")
        Button(onClick = { onCreated(created.username) }, modifier = Modifier.fillMaxWidth()) { Text("前往登录") }
        return
    }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        if (onRegister(username.trim(), password, confirmation)) { password = ""; confirmation = "" }
    }
    Text("创建个人账号", style = MaterialTheme.typography.headlineMedium)
    Text("把保存的内容，整理成自己的知识。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(username, onValueChange = { if (it.length <= 32) username = it },
            label = { Text("用户名") }, supportingText = { Text("3～32位字母、数字或下划线") },
            singleLine = true, enabled = !state.loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("register_username"))
        OutlinedTextField(password, onValueChange = { if (it.length <= 64) password = it },
            label = { Text("密码") }, supportingText = { Text("8～64个字符") },
            singleLine = true, enabled = !state.loading, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("register_password"))
        OutlinedTextField(confirmation, onValueChange = { if (it.length <= 64) confirmation = it },
            label = { Text("确认密码") }, singleLine = true, enabled = !state.loading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!state.loading) submit() }),
            modifier = Modifier.fillMaxWidth().testTag("register_confirmation"))
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("register_error"))
        }
        Button(onClick = submit, enabled = !state.loading && username.isNotBlank() && password.isNotBlank() && confirmation.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("register_submit")) {
            if (state.loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("正在创建…") }
            else Text("注册")
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回登录") }
    }
}
