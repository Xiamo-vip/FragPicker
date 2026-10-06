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

data class RegistrationUiState(val loading: Boolean = false, val createdUser: UserProfile? = null, val error: String? = null)

class RegistrationViewModel(private val repository: AuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(RegistrationUiState())
    val state = mutableState.asStateFlow()

    fun reset() { if (!state.value.loading) mutableState.value = RegistrationUiState() }

    fun register(username: String, password: String, confirmation: String): Boolean {
        if (state.value.loading) return false
        val error = when {
            !username.matches(Regex("[A-Za-z0-9_]{3,32}")) -> "用户名需为3～32位字母、数字或下划线。"
            password.length !in 8..64 -> "密码需为8～64个字符。"
            password.toByteArray(Charsets.UTF_8).size > 72 -> "密码过长，请缩短后重试。"
            password != confirmation -> "两次输入的密码不一致。"
            else -> null
        }
        if (error != null) { mutableState.value = RegistrationUiState(error = error); return false }
        mutableState.value = RegistrationUiState(loading = true)
        viewModelScope.launch {
            try { mutableState.value = RegistrationUiState(createdUser = repository.register(username, password)) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                val message = when {
                    failure is AuthApiFailure && failure.code == "USERNAME_TAKEN" -> "这个用户名已被使用，请换一个。"
                    failure is AuthApiFailure && failure.status == 400 -> "注册信息不符合要求，请检查后重试。"
                    failure is AuthApiFailure -> "注册服务暂时不可用，请稍后再试。"
                    failure is IOException -> "注册结果未能确认，请检查网络。若已创建账号，可返回登录。"
                    else -> "注册未完成，请重试。"
                }
                mutableState.value = RegistrationUiState(error = message)
            }
        }
        return true
    }
}
