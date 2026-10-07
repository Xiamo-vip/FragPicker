package com.fragpicker.android

import android.app.Application
import com.fragpicker.android.core.auth.*

class FragPickerApplication : Application() {
    // One process-wide owner keeps retained ViewModels and recreated activities on the same session.
    val authRepository by lazy { AuthRepository(AuthApi(), SessionVault(applicationContext)) }
}
