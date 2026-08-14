package com.jerrywolff.phonesyncusbc.data

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.service.notification.StatusBarNotification

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val postedAtEpochMillis: Long,
    val removedAtEpochMillis: Long?,
    val title: String?,
    val text: String?,
    val subText: String?,
    val category: String?,
    val channelId: String?,
    val ongoing: Boolean,
    val clearable: Boolean,
)

class NotificationCaptureStore(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    private val appContext = context.applicationContext

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE captured_notifications (
                notification_key TEXT PRIMARY KEY,
                package_name TEXT NOT NULL,
                app_label TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                removed_at INTEGER,
                title TEXT,
                body TEXT,
                sub_text TEXT,
                category TEXT,
                channel_id TEXT,
                ongoing INTEGER NOT NULL,
                clearable INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX notifications_posted_at ON captured_notifications(posted_at DESC)",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(notification: StatusBarNotification) {
        val extras = notification.notification.extras
        val values = ContentValues().apply {
            put("notification_key", notification.key)
            put("package_name", notification.packageName)
            put("app_label", applicationLabel(notification.packageName))
            put("posted_at", notification.postTime)
            putNull("removed_at")
            put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            put("body", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            put("sub_text", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
            put("category", notification.notification.category)
            put("channel_id", notification.notification.channelId)
            put("ongoing", if (notification.isOngoing) 1 else 0)
            put("clearable", if (notification.isClearable) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            "captured_notifications",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun markRemoved(key: String, removedAtEpochMillis: Long = System.currentTimeMillis()) {
        writableDatabase.update(
            "captured_notifications",
            ContentValues().apply { put("removed_at", removedAtEpochMillis) },
            "notification_key = ?",
            arrayOf(key),
        )
    }

    fun all(limit: Int = MAX_EXPORT_RECORDS): List<CapturedNotification> {
        return readableDatabase.query(
            "captured_notifications",
            arrayOf(
                "notification_key",
                "package_name",
                "app_label",
                "posted_at",
                "removed_at",
                "title",
                "body",
                "sub_text",
                "category",
                "channel_id",
                "ongoing",
                "clearable",
            ),
            null,
            null,
            null,
            null,
            "posted_at ASC",
            limit.coerceIn(1, MAX_EXPORT_RECORDS).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CapturedNotification(
                            key = cursor.getString(0),
                            packageName = cursor.getString(1),
                            appLabel = cursor.getString(2),
                            postedAtEpochMillis = cursor.getLong(3),
                            removedAtEpochMillis = if (cursor.isNull(4)) null else cursor.getLong(4),
                            title = if (cursor.isNull(5)) null else cursor.getString(5),
                            text = if (cursor.isNull(6)) null else cursor.getString(6),
                            subText = if (cursor.isNull(7)) null else cursor.getString(7),
                            category = if (cursor.isNull(8)) null else cursor.getString(8),
                            channelId = if (cursor.isNull(9)) null else cursor.getString(9),
                            ongoing = cursor.getInt(10) != 0,
                            clearable = cursor.getInt(11) != 0,
                        ),
                    )
                }
            }
        }
    }

    private fun applicationLabel(packageName: String): String {
        return runCatching {
            val applicationInfo = appContext.packageManager.getApplicationInfo(packageName, 0)
            appContext.packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(packageName)
    }

    private companion object {
        const val DATABASE_NAME = "captured_notifications.db"
        const val DATABASE_VERSION = 1
        const val MAX_EXPORT_RECORDS = 100_000
    }
}