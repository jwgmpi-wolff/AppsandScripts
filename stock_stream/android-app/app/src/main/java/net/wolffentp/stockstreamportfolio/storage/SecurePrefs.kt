package net.wolffentp.stockstreamportfolio.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SecurePrefs(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        "stockstream-secure-prefs",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveAccessToken(token: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun clearAccessToken() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
    }

    fun markSignInBlocked() {
        prefs.edit()
            .putBoolean(KEY_SIGN_IN_BLOCKED, true)
            .remove(KEY_ACCESS_TOKEN)
            .apply()
    }

    fun allowSignIn() {
        prefs.edit().putBoolean(KEY_SIGN_IN_BLOCKED, false).apply()
    }

    fun isSignInBlocked(): Boolean = prefs.getBoolean(KEY_SIGN_IN_BLOCKED, false)

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_SIGN_IN_BLOCKED = "sign_in_blocked"
    }
}
