package com.fragpicker.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.SessionVault
import org.junit.Assert.*
import org.junit.Test
import java.security.GeneralSecurityException

class SessionVaultTest {
    @Test
    fun encryptsRefreshTokenConsumesItOnceAndRejectsCorruption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("account_session", Context.MODE_PRIVATE)
        val vault = SessionVault(context)
        val token = "test_only_refresh_token_123456789012345678901"
        try {
            vault.clear()
            vault.save(token)
            val encrypted = preferences.getString("sealed_refresh", null)!!
            assertFalse(encrypted.contains(token))
            assertEquals(token, SessionVault(context).consume())
            assertNull(vault.consume())
            vault.save(token)
            val parts = preferences.getString("sealed_refresh", null)!!.split(':')
            val tampered = parts[0] + ":" + (if (parts[1][0] == 'A') "B" else "A") + parts[1].substring(1)
            assertTrue(preferences.edit().putString("sealed_refresh", tampered).commit())
            assertThrows(GeneralSecurityException::class.java) { vault.consume() }
            assertNull(vault.consume())
        } finally { vault.clear() }
    }
}
