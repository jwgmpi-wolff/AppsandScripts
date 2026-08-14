package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri

data class TargetSelection(
    val uri: Uri,
    val name: String,
)

class TargetSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): TargetSelection? {
        val uri = preferences.getString(KEY_URI, null)?.let(Uri::parse) ?: return null
        val name = preferences.getString(KEY_NAME, null) ?: "selected folder"
        return TargetSelection(uri, name)
    }

    fun save(uri: Uri, name: String) {
        preferences.edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_NAME, name)
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_URI).remove(KEY_NAME).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "backup_target_selection"
        const val KEY_URI = "target_uri"
        const val KEY_NAME = "target_name"
    }
}
