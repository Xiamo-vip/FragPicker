package com.fragpicker.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.feature.history.DigestRequestStore
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DigestRequestStoreTest {
    @Test fun persistedIdentityIsBoundToAccountBackendAndDateAndCorruptionIsRejected() {
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("digest_test_" + UUID.randomUUID(), Context.MODE_PRIVATE)
        try {
            val key = UUID.randomUUID().toString(); val date = "2026-10-07"
            val first = DigestRequestStore(preferences, "https://example.com", 1)
            first.save(date, key)
            assertEquals(key, DigestRequestStore(preferences, "https://example.com/", 1).load(date))
            assertNull(DigestRequestStore(preferences, "https://example.com", 2).load(date))
            assertNull(DigestRequestStore(preferences, "https://other.example.com", 1).load(date))
            assertNull(first.load("2026-10-06"))
            preferences.edit().putString("v1:https://example.com:1:$date", "corrupted").commit()
            assertThrows(IllegalArgumentException::class.java) { first.load(date) }
            first.clear(date); assertNull(first.load(date))
        } finally { preferences.edit().clear().commit() }
    }
}
