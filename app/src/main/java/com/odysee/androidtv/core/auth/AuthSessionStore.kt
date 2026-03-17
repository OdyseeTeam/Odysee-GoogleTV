package com.odysee.androidtv.core.auth

import android.content.Context
import androidx.core.content.edit

class AuthSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AuthSession? {
        val token = prefs.getString(KEY_AUTH_TOKEN, "").orEmpty()
        if (token.isBlank()) {
            return null
        }
        return AuthSession(
            authToken = token,
            userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
        )
    }

    fun save(session: AuthSession) {
        prefs.edit {
            putString(KEY_AUTH_TOKEN, session.authToken)
            putString(KEY_USER_ID, session.userId)
        }
    }

    fun clear() {
        prefs.edit {
            remove(KEY_AUTH_TOKEN)
            remove(KEY_USER_ID)
        }
    }

    companion object {
        private const val PREFS_NAME = "odysee_auth"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_USER_ID = "auth_uid"
    }
}
