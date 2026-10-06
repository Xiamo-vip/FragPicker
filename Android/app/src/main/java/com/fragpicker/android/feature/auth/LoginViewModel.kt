package com.fragpicker.android.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragpicker.android.core.auth.AuthApiFailure
import com.fragpicker.android.core.auth.AuthRepository
import com.fragpicker.android.core.auth.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

data class LoginUiState(val loading: Boolean = true, val user: UserProfile? = null, val error: String? = null)

class LoginViewModel(private val repository: AuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try { mutableState.value = LoginUiState(loading = false, user = repository.restore()) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = LoginUiState(loading = false, error = "会话恢复未完成，请重新登录。") }
        }
    }

    fun login(username: String, password: String) {
        if (state.value.loading) return
        if (!username.matches(Regex("[A-Za-z0-9_]{3,32}")) || password.isBlank()) {
            mutableState.value = LoginUiState(loading = false, error = "请输入有效用户名和密码。")
            return
        }
        mutableState.value = LoginUiState(loading = true)
        viewModelScope.launch {
            try { mutableState.value = LoginUiState(loading = false, user = repository.login(username, password)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = when {
                    error is AuthApiFailure && error.code == "INVALID_CREDENTIALS" -> "用户名或密码不正确。"
                    error is AuthApiFailure && error.status == 429 -> "尝试过于频繁，请稍后再试。"
                    error is AuthApiFailure -> "登录服务暂时不可用，请稍后再试。"
                    error is IOException -> "无法连接服务器，请检查网络后重试。"
                    else -> "登录未完成，请重试。"
                }
                mutableState.value = LoginUiState(loading = false, error = message)
            }
        }
    }
}
