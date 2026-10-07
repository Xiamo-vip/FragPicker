package com.fragpicker.android

import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.feature.retry.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class RetryRequestStoreTest {
    @Test fun identitiesRemainStableAcrossInstancesAndAccountBackendFragmentScopes() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("retry_store_test", 0)
        prefs.edit().clear().commit()
        val a = RetryRequestStore(prefs, "https://example.com", 1)
        val original = a.getOrCreate(7, true)
        assertEquals(original, RetryRequestStore(prefs, "https://example.com/", 1).getOrCreate(7, false))
        assertNull(RetryRequestStore(prefs, "https://example.com", 2).load(7))
        assertNull(RetryRequestStore(prefs, "https://another.example.com", 1).load(7)); assertNull(a.load(8))
        a.clear(7, UUID.randomUUID().toString()); assertEquals(original, a.load(7))
        a.clear(7, original.key); assertNull(a.load(7))
        prefs.edit().putString("v1:https://example.com:1:7", "{broken}").commit()
        assertThrows(Exception::class.java) { a.load(7) }
        prefs.edit().clear().commit()
    }
}
