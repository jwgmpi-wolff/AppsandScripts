package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SafGrant(
    val id: String,
    val peerId: String,
    val category: ConsentCategory,
    val uri: Uri,
    val displayName: String,
    val providerAuthority: String,
    val createdAtEpochMillis: Long,
)

class SafGrantStore(
    private val context: Context,
    private val keyManager: DeviceKeyManager,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun list(peerId: String): List<SafGrant> {
        val encrypted = preferences.getString(grantsKey(peerId), null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(keyManager.decrypt(encrypted).toString(Charsets.UTF_8))
            buildList {
                for (index in 0 until json.length()) {
                    add(json.getJSONObject(index).toSafGrant())
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(
        peerId: String,
        category: ConsentCategory,
        uri: Uri,
        displayName: String,
    ): SafGrant {
        require(category == ConsentCategory.SELECTED_FOLDERS || category == ConsentCategory.CLOUD_ACCOUNTS)
        val grant = SafGrant(
            id = UUID.randomUUID().toString(),
            peerId = peerId,
            category = category,
            uri = uri,
            displayName = displayName,
            providerAuthority = uri.authority.orEmpty(),
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        val updated = list(peerId).filterNot { it.uri == uri && it.category == category } + grant
        save(peerId, updated)
        return grant
    }

    fun remove(peerId: String, grantId: String) {
        val existing = list(peerId)
        val removed = existing.firstOrNull { it.id == grantId } ?: return
        release(removed.uri)
        save(peerId, existing.filterNot { it.id == grantId })
    }

    fun clear(peerId: String) {
        list(peerId).forEach { release(it.uri) }
        preferences.edit().remove(grantsKey(peerId)).apply()
    }

    private fun save(peerId: String, grants: List<SafGrant>) {
        val json = JSONArray(grants.map { it.toJson() })
        val encrypted = keyManager.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        preferences.edit().putString(grantsKey(peerId), encrypted).apply()
    }

    private fun release(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun SafGrant.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("peerId", peerId)
        put("category", category.name)
        put("uri", uri.toString())
        put("displayName", displayName)
        put("providerAuthority", providerAuthority)
        put("createdAtEpochMillis", createdAtEpochMillis)
    }

    private fun JSONObject.toSafGrant(): SafGrant = SafGrant(
        id = getString("id"),
        peerId = getString("peerId"),
        category = ConsentCategory.valueOf(getString("category")),
        uri = Uri.parse(getString("uri")),
        displayName = getString("displayName"),
        providerAuthority = getString("providerAuthority"),
        createdAtEpochMillis = getLong("createdAtEpochMillis"),
    )

    private fun grantsKey(peerId: String) = "grants_$peerId"

    private companion object {
        const val PREFERENCES_NAME = "authorized_document_providers"
    }
}