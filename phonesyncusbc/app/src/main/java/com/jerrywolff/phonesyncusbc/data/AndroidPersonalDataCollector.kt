package com.jerrywolff.phonesyncusbc.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.util.JsonWriter
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PersonalDataCategoryResult(
    val category: ConsentCategory,
    val records: Int,
    val bytes: Long,
    val destination: Uri? = null,
    val error: String? = null,
)

data class PersonalDataCollectionResult(
    val categories: List<PersonalDataCategoryResult>,
) {
    val exportedCategories: Int = categories.count { it.destination != null }
    val failedCategories: Int = categories.count { it.error != null }
    val records: Int = categories.sumOf { it.records }
    val bytes: Long = categories.sumOf { it.bytes }
    val firstError: String? = categories.firstNotNullOfOrNull { it.error }
}

class AndroidPersonalDataCollector(
    private val context: Context,
    private val auditLog: AuditLog,
) {
    private val resolver = context.contentResolver

    fun collect(
        categories: Set<ConsentCategory>,
        onProgress: (String) -> Unit = {},
    ): PersonalDataCollectionResult {
        val requested = categories.intersect(SUPPORTED_CATEGORIES)
        if (requested.isEmpty()) return PersonalDataCollectionResult(emptyList())

        cleanupAbandonedPendingExports()
        val peerId = "local-android"
        val sessionId = auditLog.beginSession(peerId)
        val results = requested.map { category ->
            runCatching {
                onProgress("Exporting ${category.name.lowercase().replace('_', ' ')}...")
                when (category) {
                    ConsentCategory.SMS_EXPORTS -> exportMessages(onProgress)
                    ConsentCategory.CALL_LOGS -> exportCallLog(onProgress)
                    ConsentCategory.CONTACTS -> exportContacts(onProgress)
                    ConsentCategory.CALENDAR -> exportCalendar(onProgress)
                    ConsentCategory.NOTIFICATION_EXPORTS -> exportNotifications(onProgress)
                    else -> error("Unsupported Android provider category: $category")
                }
            }.fold(
                onSuccess = { exported ->
                    auditLog.recordTransfer(
                        sessionId = sessionId,
                        peerId = peerId,
                        sourceFingerprint = fingerprint(category, exported.records),
                        category = category,
                        sourceItem = exported.displayName,
                        destination = exported.uri.toString(),
                        bytesTransferred = exported.bytes,
                        status = TransferStatus.COMPLETED,
                    )
                    PersonalDataCategoryResult(
                        category = category,
                        records = exported.records,
                        bytes = exported.bytes,
                        destination = exported.uri,
                    )
                },
                onFailure = { throwable ->
                    val error = throwable.message ?: throwable.javaClass.simpleName
                    auditLog.recordTransfer(
                        sessionId = sessionId,
                        peerId = peerId,
                        sourceFingerprint = fingerprint(category, 0),
                        category = category,
                        sourceItem = category.name,
                        destination = null,
                        bytesTransferred = 0,
                        status = TransferStatus.FAILED,
                        error = error,
                    )
                    PersonalDataCategoryResult(category, 0, 0, error = error)
                },
            )
        }

        val exported = results.filter { it.destination != null }
        val status = when {
            results.all { it.error == null } -> SyncStatus.COMPLETED
            exported.isNotEmpty() -> SyncStatus.PARTIAL
            else -> SyncStatus.FAILED
        }
        auditLog.finishSession(
            sessionId = sessionId,
            status = status,
            itemCount = exported.size,
            bytesTransferred = exported.sumOf { it.bytes },
            error = results.firstNotNullOfOrNull { it.error },
        )
        return PersonalDataCollectionResult(results)
    }

    private fun exportMessages(onProgress: (String) -> Unit): ExportedData {
        val timestamp = timestamp()
        return writeExport(
            displayName = "sms-mms-$timestamp.zip",
            mimeType = "application/zip",
            category = ConsentCategory.SMS_EXPORTS,
        ) { output ->
            val zip = ZipOutputStream(output)
            val attachments = mutableListOf<MmsAttachment>()
            var records = 0

            zip.putNextEntry(ZipEntry("messages.json"))
            JsonWriter(OutputStreamWriter(NonClosingOutputStream(zip), Charsets.UTF_8)).use { json ->
                json.setIndent("  ")
                json.beginObject()
                json.name("exportedAtEpochMillis").value(System.currentTimeMillis())
                json.name("sms").beginArray()
                resolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms._ID,
                        Telephony.Sms.THREAD_ID,
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.DATE_SENT,
                        Telephony.Sms.TYPE,
                        Telephony.Sms.READ,
                        Telephony.Sms.SEEN,
                        Telephony.Sms.STATUS,
                    ),
                    null,
                    null,
                    "${Telephony.Sms.DATE} ASC",
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        records += 1
                        if (records % PROGRESS_INTERVAL == 0) {
                            onProgress("SMS/MMS: $records message records scanned...")
                        }
                        json.beginObject()
                        json.string("id", cursor, Telephony.Sms._ID)
                        json.long("threadId", cursor, Telephony.Sms.THREAD_ID)
                        json.string("address", cursor, Telephony.Sms.ADDRESS)
                        json.string("body", cursor, Telephony.Sms.BODY)
                        json.long("dateEpochMillis", cursor, Telephony.Sms.DATE)
                        json.long("dateSentEpochMillis", cursor, Telephony.Sms.DATE_SENT)
                        json.int("type", cursor, Telephony.Sms.TYPE)
                        json.int("read", cursor, Telephony.Sms.READ)
                        json.int("seen", cursor, Telephony.Sms.SEEN)
                        json.int("status", cursor, Telephony.Sms.STATUS)
                        json.endObject()
                    }
                }
                json.endArray()

                json.name("mms").beginArray()
                val mmsMessages = resolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(
                        Telephony.Mms._ID,
                        Telephony.Mms.THREAD_ID,
                        Telephony.Mms.DATE,
                        Telephony.Mms.DATE_SENT,
                        Telephony.Mms.MESSAGE_BOX,
                        Telephony.Mms.READ,
                        Telephony.Mms.SEEN,
                        Telephony.Mms.SUBJECT,
                        Telephony.Mms.CONTENT_TYPE,
                    ),
                    null,
                    null,
                    "${Telephony.Mms.DATE} ASC",
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                MmsMessage(
                                    id = cursor.requiredString(Telephony.Mms._ID),
                                    threadId = cursor.nullableLong(Telephony.Mms.THREAD_ID),
                                    dateEpochMillis = cursor.nullableLong(Telephony.Mms.DATE)?.times(1_000),
                                    dateSentEpochMillis = cursor.nullableLong(Telephony.Mms.DATE_SENT)?.times(1_000),
                                    messageBox = cursor.nullableLong(Telephony.Mms.MESSAGE_BOX),
                                    read = cursor.nullableLong(Telephony.Mms.READ),
                                    seen = cursor.nullableLong(Telephony.Mms.SEEN),
                                    subject = cursor.nullableString(Telephony.Mms.SUBJECT),
                                    contentType = cursor.nullableString(Telephony.Mms.CONTENT_TYPE),
                                ),
                            )
                        }
                    }
                }.orEmpty()
                mmsMessages.forEach { message ->
                    records += 1
                    if (records % PROGRESS_INTERVAL == 0) {
                        onProgress("SMS/MMS: $records message records scanned...")
                    }
                    json.beginObject()
                    json.name("id").value(message.id)
                    json.name("threadId").value(message.threadId)
                    json.name("dateEpochMillis").value(message.dateEpochMillis)
                    json.name("dateSentEpochMillis").value(message.dateSentEpochMillis)
                    json.name("messageBox").value(message.messageBox)
                    json.name("read").value(message.read)
                    json.name("seen").value(message.seen)
                    json.name("subject").value(message.subject)
                    json.name("contentType").value(message.contentType)
                    writeMmsAddresses(json, message.id)
                    writeMmsParts(json, message.id, attachments)
                    json.endObject()
                }
                json.endArray()
                json.endObject()
            }
            zip.closeEntry()

            attachments.forEachIndexed { index, attachment ->
                onProgress("SMS/MMS attachments: ${index + 1} of ${attachments.size}...")
                zip.putNextEntry(ZipEntry(attachment.path))
                resolver.openInputStream(attachment.uri)?.use { it.copyTo(zip) }
                    ?: error("Could not read MMS attachment ${attachment.path}.")
                zip.closeEntry()
            }
            zip.finish()
            records
        }
    }

    private fun writeMmsAddresses(json: JsonWriter, messageId: String) {
        json.name("addresses").beginArray()
        resolver.query(
            Uri.parse("content://mms/$messageId/addr"),
            arrayOf("address", "type", "charset"),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                json.beginObject()
                json.string("address", cursor, "address")
                json.int("type", cursor, "type")
                json.int("charset", cursor, "charset")
                json.endObject()
            }
        }
        json.endArray()
    }

    private fun writeMmsParts(
        json: JsonWriter,
        messageId: String,
        attachments: MutableList<MmsAttachment>,
    ) {
        json.name("parts").beginArray()
        resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "name", "fn", "text", "cl", "cid", "_data"),
            "mid = ?",
            arrayOf(messageId),
            "_id ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val partId = cursor.requiredString("_id")
                val contentType = cursor.nullableString("ct") ?: "application/octet-stream"
                val suppliedName = cursor.nullableString("name")
                    ?: cursor.nullableString("fn")
                    ?: cursor.nullableString("cl")
                val inlineText = cursor.nullableString("text")
                val hasBinaryData = !cursor.nullableString("_data").isNullOrBlank()
                json.beginObject()
                json.name("id").value(partId)
                json.name("contentType").value(contentType)
                json.name("name").value(suppliedName)
                json.string("contentId", cursor, "cid")
                if (!inlineText.isNullOrEmpty()) {
                    json.name("text").value(inlineText)
                }
                if (hasBinaryData) {
                    val fileName = sanitizeName(suppliedName ?: "part-$partId")
                    val path = "mms/$messageId/$partId-$fileName"
                    json.name("attachmentPath").value(path)
                    attachments += MmsAttachment(Uri.parse("content://mms/part/$partId"), path)
                } else {
                    json.name("binaryDataAvailable").value(false)
                }
                json.endObject()
            }
        }
        json.endArray()
    }

    private fun exportCallLog(onProgress: (String) -> Unit): ExportedData {
        return writeJsonExport("call-log-${timestamp()}.json", ConsentCategory.CALL_LOGS) { json ->
            var records = 0
            json.beginObject()
            json.name("exportedAtEpochMillis").value(System.currentTimeMillis())
            json.name("calls").beginArray()
            resolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.NEW,
                    CallLog.Calls.IS_READ,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                    CallLog.Calls.COUNTRY_ISO,
                    CallLog.Calls.GEOCODED_LOCATION,
                    CallLog.Calls.FEATURES,
                ),
                null,
                null,
                "${CallLog.Calls.DATE} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    records += 1
                    if (records % PROGRESS_INTERVAL == 0) onProgress("Call history: $records records scanned...")
                    json.beginObject()
                    json.string("id", cursor, CallLog.Calls._ID)
                    json.string("number", cursor, CallLog.Calls.NUMBER)
                    json.string("cachedName", cursor, CallLog.Calls.CACHED_NAME)
                    json.int("type", cursor, CallLog.Calls.TYPE)
                    json.long("dateEpochMillis", cursor, CallLog.Calls.DATE)
                    json.long("durationSeconds", cursor, CallLog.Calls.DURATION)
                    json.int("new", cursor, CallLog.Calls.NEW)
                    json.int("isRead", cursor, CallLog.Calls.IS_READ)
                    json.string("phoneAccountId", cursor, CallLog.Calls.PHONE_ACCOUNT_ID)
                    json.string("countryIso", cursor, CallLog.Calls.COUNTRY_ISO)
                    json.string("location", cursor, CallLog.Calls.GEOCODED_LOCATION)
                    json.int("features", cursor, CallLog.Calls.FEATURES)
                    json.endObject()
                }
            }
            json.endArray()
            json.endObject()
            records
        }
    }

    private fun exportContacts(onProgress: (String) -> Unit): ExportedData {
        return writeExport(
            displayName = "contacts-${timestamp()}.vcf",
            mimeType = "text/vcard",
            category = ConsentCategory.CONTACTS,
        ) { output ->
            var records = 0
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts.LOOKUP_KEY,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ),
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val lookupKey = cursor.nullableString(ContactsContract.Contacts.LOOKUP_KEY)
                    if (lookupKey == null) continue
                    val vcardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
                    resolver.openInputStream(vcardUri)?.use { it.copyTo(output) }
                        ?: error("Could not read contact ${cursor.nullableString(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)}.")
                    output.write("\r\n".toByteArray())
                    records += 1
                    if (records % PROGRESS_INTERVAL == 0) onProgress("Contacts: $records records exported...")
                }
            }
            records
        }
    }

    private fun exportCalendar(onProgress: (String) -> Unit): ExportedData {
        return writeJsonExport("calendar-${timestamp()}.json", ConsentCategory.CALENDAR) { json ->
            var records = 0
            json.beginObject()
            json.name("exportedAtEpochMillis").value(System.currentTimeMillis())
            json.name("events").beginArray()
            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.EVENT_TIMEZONE,
                    CalendarContract.Events.RRULE,
                    CalendarContract.Events.RDATE,
                    CalendarContract.Events.STATUS,
                    CalendarContract.Events.ORGANIZER,
                    CalendarContract.Events.AVAILABILITY,
                    CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Events.ACCOUNT_NAME,
                    CalendarContract.Events.ACCOUNT_TYPE,
                ),
                null,
                null,
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    records += 1
                    if (records % PROGRESS_INTERVAL == 0) onProgress("Calendar: $records events scanned...")
                    json.beginObject()
                    json.string("id", cursor, CalendarContract.Events._ID)
                    json.string("title", cursor, CalendarContract.Events.TITLE)
                    json.string("description", cursor, CalendarContract.Events.DESCRIPTION)
                    json.string("location", cursor, CalendarContract.Events.EVENT_LOCATION)
                    json.long("startEpochMillis", cursor, CalendarContract.Events.DTSTART)
                    json.long("endEpochMillis", cursor, CalendarContract.Events.DTEND)
                    json.int("allDay", cursor, CalendarContract.Events.ALL_DAY)
                    json.string("timeZone", cursor, CalendarContract.Events.EVENT_TIMEZONE)
                    json.string("recurrenceRule", cursor, CalendarContract.Events.RRULE)
                    json.string("recurrenceDates", cursor, CalendarContract.Events.RDATE)
                    json.int("status", cursor, CalendarContract.Events.STATUS)
                    json.string("organizer", cursor, CalendarContract.Events.ORGANIZER)
                    json.int("availability", cursor, CalendarContract.Events.AVAILABILITY)
                    json.string("calendar", cursor, CalendarContract.Events.CALENDAR_DISPLAY_NAME)
                    json.string("accountName", cursor, CalendarContract.Events.ACCOUNT_NAME)
                    json.string("accountType", cursor, CalendarContract.Events.ACCOUNT_TYPE)
                    json.endObject()
                }
            }
            json.endArray()
            json.endObject()
            records
        }
    }

    private fun exportNotifications(onProgress: (String) -> Unit): ExportedData {
        val notifications = NotificationCaptureStore(context).all()
        onProgress("Notifications: ${notifications.size} captured records ready...")
        return writeJsonExport(
            "notifications-${timestamp()}.json",
            ConsentCategory.NOTIFICATION_EXPORTS,
        ) { json ->
            json.beginObject()
            json.name("exportedAtEpochMillis").value(System.currentTimeMillis())
            json.name("scope").value("Active notifications at approval plus future notifications captured by Phone Sync")
            json.name("notifications").beginArray()
            notifications.forEach { notification ->
                json.beginObject()
                json.name("key").value(notification.key)
                json.name("packageName").value(notification.packageName)
                json.name("appLabel").value(notification.appLabel)
                json.name("postedAtEpochMillis").value(notification.postedAtEpochMillis)
                json.name("removedAtEpochMillis").value(notification.removedAtEpochMillis)
                json.name("title").value(notification.title)
                json.name("text").value(notification.text)
                json.name("subText").value(notification.subText)
                json.name("category").value(notification.category)
                json.name("channelId").value(notification.channelId)
                json.name("ongoing").value(notification.ongoing)
                json.name("clearable").value(notification.clearable)
                json.endObject()
            }
            json.endArray()
            json.endObject()
            notifications.size
        }
    }

    private fun writeJsonExport(
        displayName: String,
        category: ConsentCategory,
        writer: (JsonWriter) -> Int,
    ): ExportedData {
        return writeExport(displayName, "application/json", category) { output ->
            JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { json ->
                json.setIndent("  ")
                writer(json)
            }
        }
    }

    private fun writeExport(
        displayName: String,
        mimeType: String,
        category: ConsentCategory,
        writer: (OutputStream) -> Int,
    ): ExportedData {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/Phone Sync/This Android/${category.name.lowercase()}",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android could not create $displayName.")
        try {
            val rawOutput = resolver.openOutputStream(destination, "w")
                ?: error("Android could not open $displayName.")
            val countingOutput = CountingOutputStream(rawOutput)
            val records = countingOutput.use(writer)
            resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return ExportedData(destination, displayName, records, countingOutput.bytesWritten)
        } catch (throwable: Throwable) {
            resolver.delete(destination, null, null)
            throw throwable
        }
    }

    private fun cleanupAbandonedPendingExports() {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 1",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/Phone Sync/This Android/%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                resolver.delete(
                    Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0).toString(),
                    ),
                    null,
                    null,
                )
            }
        }
    }

    private fun fingerprint(category: ConsentCategory, records: Int): String {
        return MessageDigest.getInstance("SHA-256")
            .digest("${category.name}|$records|${System.currentTimeMillis()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun sanitizeName(value: String): String {
        return value.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120).ifBlank { "attachment" }
    }

    private data class ExportedData(
        val uri: Uri,
        val displayName: String,
        val records: Int,
        val bytes: Long,
    )

    private data class MmsAttachment(val uri: Uri, val path: String)

    private data class MmsMessage(
        val id: String,
        val threadId: Long?,
        val dateEpochMillis: Long?,
        val dateSentEpochMillis: Long?,
        val messageBox: Long?,
        val read: Long?,
        val seen: Long?,
        val subject: String?,
        val contentType: String?,
    )

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var bytesWritten: Long = 0
            private set

        override fun write(value: Int) {
            out.write(value)
            bytesWritten += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            bytesWritten += length
        }
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }

    private fun Cursor.requiredString(column: String): String {
        return nullableString(column) ?: error("Missing required provider column $column.")
    }

    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private fun JsonWriter.string(name: String, cursor: Cursor, column: String) {
        name(name).value(cursor.nullableString(column))
    }

    private fun JsonWriter.long(name: String, cursor: Cursor, column: String) {
        name(name).value(cursor.nullableLong(column))
    }

    private fun JsonWriter.int(name: String, cursor: Cursor, column: String) {
        name(name).value(cursor.nullableLong(column))
    }

    companion object {
        private const val PROGRESS_INTERVAL = 100

        val SUPPORTED_CATEGORIES = setOf(
            ConsentCategory.SMS_EXPORTS,
            ConsentCategory.CALL_LOGS,
            ConsentCategory.CONTACTS,
            ConsentCategory.CALENDAR,
            ConsentCategory.NOTIFICATION_EXPORTS,
        )

        fun requiredPermissions(categories: Set<ConsentCategory>): Set<String> = buildSet {
            if (ConsentCategory.SMS_EXPORTS in categories) add(Manifest.permission.READ_SMS)
            if (ConsentCategory.CALL_LOGS in categories) add(Manifest.permission.READ_CALL_LOG)
            if (ConsentCategory.CONTACTS in categories) add(Manifest.permission.READ_CONTACTS)
            if (ConsentCategory.CALENDAR in categories) add(Manifest.permission.READ_CALENDAR)
        }
    }
}