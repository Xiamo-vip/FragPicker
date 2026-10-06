package com.fragpicker.android.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AuthRepository(private val api: AuthApi, private val vault: SessionVault) {
    private val mutex = Mutex()
    private var activeSession: LoginSession? = null

    suspend fun restore(): UserProfile? = mutex.withLock {
        activeSession?.let { return@withLock it.user }
        val token = withContext(Dispatchers.IO) { vault.consume() } ?: return@withLock null
        activate(api.refresh(token))
    }

    suspend fun login(username: String, password: String): UserProfile = mutex.withLock {
        activate(api.login(username, password))
    }

    suspend fun register(username: String, password: String): UserProfile = mutex.withLock {
        api.register(username, password)
    }

    suspend fun logout(): Boolean = mutex.withLock {
        try {
            val session = activeSession ?: return@withLock false
            try {
                api.logout(session.accessToken)
            } catch (failure: AuthApiFailure) {
                if (failure.status != 401) throw failure
                // One refresh if the access token expired; consume the persisted credential once.
                val token = withContext(Dispatchers.IO) { vault.consume() } ?: throw failure
                api.logout(api.refresh(token).accessToken)
            }
            true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        } finally {
            activeSession = null
            withContext(NonCancellable + Dispatchers.IO) { vault.clear() }
        }
    }

    private suspend fun activate(session: LoginSession): UserProfile {
        val user = api.currentUser(session.accessToken)
        check(user.id == session.user.id) { "Session user mismatch" }
        withContext(Dispatchers.IO) { vault.save(session.refreshToken) }
        activeSession = LoginSession(session.accessToken, session.refreshToken, user)
        return user
    }
}
