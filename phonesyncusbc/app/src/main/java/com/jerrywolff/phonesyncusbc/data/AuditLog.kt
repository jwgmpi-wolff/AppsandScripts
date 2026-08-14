package com.jerrywolff.phonesyncusbc.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory

const val LOCAL_ANDROID_PEER_ID = "local-android"

fun isExternalSourcePeer(peerId: String): Boolean = peerId != LOCAL_ANDROID_PEER_ID

enum class SyncStatus {
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
}

enum class TransferStatus {
    COMPLETED,
    FAILED,
    SKIPPED,
}

data class AuditEntry(
    val id: Long,
    val transferredAtEpochMillis: Long,
    val category: ConsentCategory,
    val sourceItem: String,
    val destination: String?,
    val bytesTransferred: Long,
    val status: TransferStatus,
    val error: String?,
)

fun AuditEntry.displayName(): String {
    return destination?.substringAfterLast('/')?.substringBefore('?')
        ?.takeIf { it.isNotBlank() }
        ?: sourceItem.substringAfterLast('/').ifBlank { "Imported item" }
}

fun AuditEntry.storageLocation(): String {
    return destination ?: "Collected item is no longer available"
}

data class SyncSummary(
    val id: Long,
    val completedAtEpochMillis: Long?,
    val status: SyncStatus,
    val itemCount: Int,
    val bytesTransferred: Long,
)

class AuditLog(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE sync_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_id TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                status TEXT NOT NULL,
                item_count INTEGER NOT NULL DEFAULT 0,
                bytes_transferred INTEGER NOT NULL DEFAULT 0,
                error TEXT
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE transfers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                peer_id TEXT NOT NULL,
                source_fingerprint TEXT NOT NULL,
                transferred_at INTEGER NOT NULL,
                category TEXT NOT NULL,
                source_item TEXT NOT NULL,
                destination TEXT,
                bytes_transferred INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                error TEXT,
                FOREIGN KEY(session_id) REFERENCES sync_sessions(id)
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX transfers_peer_time ON transfers(peer_id, transferred_at DESC)",
        )
        database.execSQL(
            "CREATE INDEX transfers_fingerprint ON transfers(peer_id, source_fingerprint, status)",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun beginSession(peerId: String): Long {
        val values = ContentValues().apply {
            put("peer_id", peerId)
            put("started_at", System.currentTimeMillis())
            put("status", SyncStatus.RUNNING.name)
        }
        return writableDatabase.insertOrThrow("sync_sessions", null, values)
    }

    fun finishSession(
        sessionId: Long,
        status: SyncStatus,
        itemCount: Int,
        bytesTransferred: Long,
        error: String? = null,
    ) {
        val values = ContentValues().apply {
            put("completed_at", System.currentTimeMillis())
            put("status", status.name)
            put("item_count", itemCount)
            put("bytes_transferred", bytesTransferred)
            put("error", error)
        }
        writableDatabase.update("sync_sessions", values, "id = ?", arrayOf(sessionId.toString()))
    }

    fun recordTransfer(
        sessionId: Long,
        peerId: String,
        sourceFingerprint: String,
        category: ConsentCategory,
        sourceItem: String,
        destination: String?,
        bytesTransferred: Long,
        status: TransferStatus,
        error: String? = null,
    ) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("peer_id", peerId)
            put("source_fingerprint", sourceFingerprint)
            put("transferred_at", System.currentTimeMillis())
            put("category", category.name)
            put("source_item", sourceItem)
            put("destination", destination)
            put("bytes_transferred", bytesTransferred)
            put("status", status.name)
            put("error", error)
        }
        writableDatabase.insertOrThrow("transfers", null, values)
    }

    fun wasTransferred(peerId: String, sourceFingerprint: String): Boolean {
        readableDatabase.query(
            "transfers",
            arrayOf("id"),
            "peer_id = ? AND source_fingerprint = ? AND status = ?",
            arrayOf(peerId, sourceFingerprint, TransferStatus.COMPLETED.name),
            null,
            null,
            null,
            "1",
        ).use { cursor -> return cursor.moveToFirst() }
    }

    fun latestSession(peerId: String): SyncSummary? {
        readableDatabase.query(
            "sync_sessions",
            arrayOf("id", "completed_at", "status", "item_count", "bytes_transferred"),
            "peer_id = ? AND completed_at IS NOT NULL",
            arrayOf(peerId),
            null,
            null,
            "completed_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return SyncSummary(
                id = cursor.getLong(0),
                completedAtEpochMillis = cursor.getLong(1),
                status = SyncStatus.valueOf(cursor.getString(2)),
                itemCount = cursor.getInt(3),
                bytesTransferred = cursor.getLong(4),
            )
        }
    }

    fun recentTransfers(peerId: String, limit: Int = 100): List<AuditEntry> {
        readableDatabase.query(
            "transfers",
            arrayOf(
                "id",
                "transferred_at",
                "category",
                "source_item",
                "destination",
                "bytes_transferred",
                "status",
                "error",
            ),
            "peer_id = ?",
            arrayOf(peerId),
            null,
            null,
            "transferred_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        AuditEntry(
                            id = cursor.getLong(0),
                            transferredAtEpochMillis = cursor.getLong(1),
                            category = ConsentCategory.valueOf(cursor.getString(2)),
                            sourceItem = cursor.getString(3),
                            destination = cursor.getString(4),
                            bytesTransferred = cursor.getLong(5),
                            status = TransferStatus.valueOf(cursor.getString(6)),
                            error = cursor.getString(7),
                        ),
                    )
                }
            }
        }
    }

    fun completedTransfers(peerId: String, limit: Int = 500): List<AuditEntry> {
        readableDatabase.query(
            "transfers",
            arrayOf(
                "id",
                "transferred_at",
                "category",
                "source_item",
                "destination",
                "bytes_transferred",
                "status",
                "error",
            ),
            "peer_id = ? AND status = ? AND destination IS NOT NULL",
            arrayOf(peerId, TransferStatus.COMPLETED.name),
            null,
            null,
            "transferred_at DESC",
            limit.coerceIn(1, 1_000).toString(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        AuditEntry(
                            id = cursor.getLong(0),
                            transferredAtEpochMillis = cursor.getLong(1),
                            category = ConsentCategory.valueOf(cursor.getString(2)),
                            sourceItem = cursor.getString(3),
                            destination = cursor.getString(4),
                            bytesTransferred = cursor.getLong(5),
                            status = TransferStatus.valueOf(cursor.getString(6)),
                            error = cursor.getString(7),
                        ),
                    )
                }
            }
        }
    }

    fun completedExternalTransfers(peerId: String?, limit: Int = 500): List<AuditEntry> {
        if (peerId == null || !isExternalSourcePeer(peerId)) return emptyList()
        return completedTransfers(peerId, limit)
    }

    fun latestExternalPeerId(): String? {
        readableDatabase.query(
            "transfers",
            arrayOf("peer_id"),
            "peer_id != ? AND status = ? AND destination IS NOT NULL",
            arrayOf(LOCAL_ANDROID_PEER_ID, TransferStatus.COMPLETED.name),
            null,
            null,
            "transferred_at DESC",
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun clear(peerId: String) {
        writableDatabase.delete("transfers", "peer_id = ?", arrayOf(peerId))
        writableDatabase.delete("sync_sessions", "peer_id = ?", arrayOf(peerId))
    }

    private companion object {
        const val DATABASE_NAME = "transfer_audit.db"
        const val DATABASE_VERSION = 1
    }
}