package com.jerrywolff.phonesyncusbc.domain

object TransferClassifier {
    private val imageExtensions = setOf("bmp", "dng", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
    private val videoExtensions = setOf("3g2", "3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")
    private val passwordExportMarkers = listOf(
        "/password/",
        "/passwords/",
        "/password_exports/",
        "/password-exports/",
        "keepass",
        "bitwarden",
        "1password",
        "lastpass",
        "dashlane",
        "protonpass",
        "enpass",
        "passwordsafe",
        "password-vault",
    )

    fun classify(path: String): ConsentCategory {
        val normalizedPath = path.replace('\\', '/').lowercase()
        val extension = normalizedPath.substringAfterLast('.', missingDelimiterValue = "")
        val fileName = normalizedPath.substringAfterLast('/')

        return when {
            isProtectedPrivateDatabase(normalizedPath) -> ConsentCategory.DOCUMENTS
            isSmsExport(normalizedPath, fileName) -> ConsentCategory.SMS_EXPORTS
            isCallLogExport(normalizedPath, fileName) -> ConsentCategory.CALL_LOGS
            isCalendarExport(normalizedPath, fileName) -> ConsentCategory.CALENDAR
            isPasswordExport(normalizedPath, fileName) -> ConsentCategory.PASSWORD_EXPORTS
            isVoicemailExport(normalizedPath, fileName) -> ConsentCategory.VOICEMAIL_EXPORTS
            isChatExport(normalizedPath, fileName) -> ConsentCategory.CHAT_EXPORTS
            isEmailExport(normalizedPath, fileName) -> ConsentCategory.EMAIL_EXPORTS
            isNotificationExport(normalizedPath, fileName) -> ConsentCategory.NOTIFICATION_EXPORTS
            extension == "vcf" || extension == "vcard" -> ConsentCategory.CONTACTS
            extension in imageExtensions || extension in videoExtensions -> ConsentCategory.PHOTOS_AND_VIDEOS
            else -> ConsentCategory.DOCUMENTS
        }
    }

    private fun isSmsExport(path: String, fileName: String): Boolean {
        return (
            "/sms/" in path || "/sms_exports/" in path || "sms-export" in path ||
                "sms_backup" in path || "smsbackup" in path || fileName.startsWith("sms-mms-")
            ) &&
            fileName.substringAfterLast('.', "") in setOf("xml", "json", "csv", "txt", "html", "zip")
    }

    private fun isCallLogExport(path: String, fileName: String): Boolean {
        return (
            "/call_logs/" in path || "/call-logs/" in path || "call-log" in fileName ||
                "call_history" in fileName
            ) && fileName.substringAfterLast('.', "") in setOf("json", "xml", "csv", "txt", "html", "zip")
    }

    private fun isCalendarExport(path: String, fileName: String): Boolean {
        return (
            "/calendar/" in path || "/calendars/" in path || fileName.startsWith("calendar-")
            ) && fileName.substringAfterLast('.', "") in setOf("json", "xml", "csv", "txt", "html", "ics", "ical", "zip")
    }

    private fun isPasswordExport(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        if (extension in setOf("kdb", "kdbx", "psafe3", "enpassbackup", "1pux")) return true
        val passwordExportName = passwordExportMarkers.any { it in path || it in fileName }
        return passwordExportName && extension in setOf(
            "json",
            "csv",
            "xml",
            "zip",
            "db",
            "sqlite",
            "sqlite3",
        )
    }

    private fun isVoicemailExport(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        val voicemailName = listOf(
            "/voicemail/",
            "/voicemails/",
            "/voicemail_exports/",
            "/voicemail-exports/",
            "visual-voicemail",
            "visual_voicemail",
            "voicemail-",
            "voicemail_",
        ).any { it in path || it in fileName }
        return voicemailName && extension in setOf(
            "3gp",
            "aac",
            "amr",
            "m4a",
            "mp3",
            "ogg",
            "opus",
            "wav",
            "json",
            "xml",
            "zip",
        )
    }

    private fun isChatExport(path: String, fileName: String): Boolean {
        val chatName = listOf("whatsapp", "signal", "telegram", "messenger", "chat", "conversation")
            .any { it in path || it in fileName }
        return chatName && fileName.substringAfterLast('.', "") in setOf("txt", "json", "html", "xml", "zip", "csv", "db")
    }

    private fun isEmailExport(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        if (extension in setOf("eml", "mbox", "msg", "pst")) return true
        val mailName = listOf("email", "mail", "gmail", "outlook", "thunderbird", "inbox", "sent")
            .any { it in path || it in fileName }
        return mailName && extension in setOf("txt", "html", "json", "xml", "zip")
    }

    private fun isNotificationExport(path: String, fileName: String): Boolean {
        return ("notification" in path || "notifications" in path || "notification" in fileName) &&
            fileName.substringAfterLast('.', "") in setOf("json", "xml", "csv", "txt", "html", "zip")
    }

    fun isVideo(path: String): Boolean {
        return path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in videoExtensions
    }

    fun isProtectedPrivateDatabase(path: String): Boolean {
        val normalizedPath = path.replace('\\', '/').lowercase()
        val extension = normalizedPath.substringAfterLast('.', missingDelimiterValue = "")
        val privateLocation = listOf(
            "/android/data/",
            "/data/data/",
            "/private/var/mobile/containers/",
        ).any(normalizedPath::contains)
        val privatePasswordStore = passwordExportMarkers.any(normalizedPath::contains)
        val privateVoicemailStore = listOf("voicemail", "dialer", "telecom").any(normalizedPath::contains)
        return privateLocation && (
            privatePasswordStore || privateVoicemailStore || extension in setOf(
                "db",
                "sqlite",
                "sqlite3",
                "kdb",
                "kdbx",
                "psafe3",
                "enpassbackup",
                "1pux",
            )
        )
    }
}