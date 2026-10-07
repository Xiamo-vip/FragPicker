package com.fragpicker.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking

internal fun cleanupRealSession() {
    if (InstrumentationRegistry.getArguments().getString("realBackend") == "true") {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FragPickerApplication
        runBlocking { app.authRepository.logout() }
    }
}
