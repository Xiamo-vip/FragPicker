package com.fragpicker.android.core.auth

import kotlinx.coroutines.Dispatchers
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

    private suspend fun activate(session: LoginSession): UserProfile {
        val user = api.currentUser(session.accessToken)
        check(user.id == session.user.id) { "Session user mismatch" }
        withContext(Dispatchers.IO) { vault.save(session.refreshToken) }
        activeSession = LoginSession(session.accessToken, session.refreshToken, user)
        return user
    }
}
