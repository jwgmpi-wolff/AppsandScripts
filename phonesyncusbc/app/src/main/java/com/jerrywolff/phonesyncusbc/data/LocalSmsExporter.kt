package com.jerrywolff.phonesyncusbc.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.BufferedWriter
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

data class LocalSmsExportProgress(
    val exportedMessages: Int,
    val totalMessages: Int,
    val bytesWritten: Long,
    val currentAddress: String? = null,
)

data class LocalSmsExportResult(
    val uri: Uri? = null,
    val displayName: String? = null,
    val messagesExported: Int = 0,
    val bytesExported: Long = 0,
    val sha256: String? = null,
    val error: String? = null,
)

internal data class LocalSmsRecord(
    val id: Long?,
    val address: String?,
    val body: String?,
    val type: Int?,
    val dateEpochMillis: Long?,
    val dateSentEpochMillis: Long?,
    val providerValues: Map<String, Any?>,
)

internal fun serializeLocalSmsRecord(record: LocalSmsRecord): String {
    val document = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("recordType", "android-sms")
        addNullableProperty("id", record.id)
        addNullableProperty("address", record.address)
        addNullableProperty("body", record.body)
        addNullableProperty("type", record.type)
        addProperty("typeLabel", smsTypeLabel(record.type))
        addNullableProperty("dateEpochMillis", record.dateEpochMillis)
        addNullableProperty("dateSentEpochMillis", record.dateSentEpochMillis)
        record.dateEpochMillis?.takeIf { it > 0 }?.let { epochMillis ->
            addProperty("timestamp", Instant.ofEpochMilli(epochMillis).toString())
        }
        val provider = JsonObject()
        record.providerValues.toSortedMap().forEach { (column, value) ->
            provider.addNullableProperty(column, value)
        }
        add("provider", provider)
    }
    return gson.toJson(document)
}

internal fun smsTypeLabel(type: Int?): String = when (type) {
    1 -> "inbox"
    2 -> "sent"
    3 -> "draft"
    4 -> "outbox"
    5 -> "failed"
    6 -> "queued"
    else -> "unknown"
}

class LocalSmsExporter(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun exportAll(
        onProgress: (LocalSmsExportProgress) -> Unit = {},
    ): LocalSmsExportResult {
        val resolver = context.contentResolver
        val cursor = runCatching {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                null,
                null,
                null,
                "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC",
            )
        }.getOrElse { throwable ->
            return LocalSmsExportResult(error = throwable.message ?: throwable.javaClass.simpleName)
        } ?: return LocalSmsExportResult(error = "Android did not return the SMS provider.")

        cursor.use { smsCursor ->
            val displayName = "sms-${timestamp()}.jsonl"
            val destination = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/x-ndjson")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/Phone Sync USB-C/This Android/sms_exports",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: return LocalSmsExportResult(error = "Android could not create the SMS export file.")

            var messages = 0
            var bytesWritten = 0L
            return try {
                resolver.openOutputStream(destination, "w").use { output ->
                    checkNotNull(output) { "Android could not open the SMS export file." }
                    val countingOutput = CountingOutputStream(output)
                    BufferedWriter(OutputStreamWriter(countingOutput, Charsets.UTF_8)).use { writer ->
                        val totalMessages = smsCursor.count
                        while (smsCursor.moveToNext()) {
                            val record = smsCursor.toLocalSmsRecord()
                            writer.append(serializeLocalSmsRecord(record))
                            writer.newLine()
                            messages += 1
                            if (messages % PROGRESS_FLUSH_INTERVAL == 0) writer.flush()
                            onProgress(
                                LocalSmsExportProgress(
                                    exportedMessages = messages,
                                    totalMessages = totalMessages,
                                    bytesWritten = countingOutput.bytesWritten,
                                    currentAddress = record.address,
                                ),
                            )
                        }
                        writer.flush()
                    }
                    bytesWritten = countingOutput.bytesWritten
                }
                val integrity = calculateIntegrity(destination)
                check(integrity.bytes == bytesWritten) { "The SMS export size changed during verification." }
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                onProgress(LocalSmsExportProgress(messages, smsCursor.count, bytesWritten))
                LocalSmsExportResult(
                    uri = destination,
                    displayName = displayName,
                    messagesExported = messages,
                    bytesExported = bytesWritten,
                    sha256 = integrity.sha256,
                )
            } catch (throwable: Throwable) {
                resolver.delete(destination, null, null)
                LocalSmsExportResult(
                    messagesExported = messages,
                    bytesExported = bytesWritten,
                    error = throwable.message ?: throwable.javaClass.simpleName,
                )
            }
        }
    }

    private fun Cursor.toLocalSmsRecord(): LocalSmsRecord {
        val values = linkedMapOf<String, Any?>()
        columnNames.forEachIndexed { index, name -> values[name] = valueAt(index) }
        return LocalSmsRecord(
            id = values["_id"].asLong(),
            address = values["address"] as? String,
            body = values["body"] as? String,
            type = values["type"].asLong()?.toInt(),
            dateEpochMillis = values["date"].asLong(),
            dateSentEpochMillis = values["date_sent"].asLong(),
            providerValues = values,
        )
    }

    private fun Cursor.valueAt(index: Int): Any? = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_STRING -> getString(index)
        Cursor.FIELD_TYPE_BLOB -> "base64:${Base64.encodeToString(getBlob(index), Base64.NO_WRAP)}"
        else -> getString(index)
    }

    private fun calculateIntegrity(uri: Uri): ExportIntegrity {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "Android could not read the completed SMS export." }
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                bytes += read
            }
        }
        return ExportIntegrity(
            bytes = bytes,
            sha256 = digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            },
        )
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }

    private data class ExportIntegrity(val bytes: Long, val sha256: String)

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var bytesWritten: Long = 0
            private set

        override fun write(byte: Int) {
            out.write(byte)
            bytesWritten += 1
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            out.write(bytes, offset, length)
            bytesWritten += length
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_FLUSH_INTERVAL = 50

        fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }
}

private fun JsonObject.addNullableProperty(name: String, value: Any?) {
    when (value) {
        null -> add(name, JsonNull.INSTANCE)
        is String -> addProperty(name, value)
        is Number -> addProperty(name, value)
        is Boolean -> addProperty(name, value)
        else -> addProperty(name, value.toString())
    }
}

private val gson = GsonBuilder().serializeNulls().create()