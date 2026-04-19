package com.cloudagentos.app.auth

import android.content.Context

/**
 * Simple API key authentication.
 * User provides their own OpenAI or Anthropic API key via Settings.
 */
class ApiKeyAuth(private val context: Context) {

    private val prefs = context.getSharedPreferences("deka_auth", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    val isAuthenticated: Boolean
        get() = apiKey.isNotEmpty()

    suspend fun getValidToken(): String? {
        val key = apiKey
        return if (key.isNotEmpty()) key else null
    }

    fun logout() {
        apiKey = ""
    }
}
