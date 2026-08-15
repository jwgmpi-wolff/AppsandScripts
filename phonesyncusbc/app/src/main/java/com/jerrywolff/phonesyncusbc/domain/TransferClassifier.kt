package com.jerrywolff.phonesyncusbc.domain

object TransferClassifier {
    private val imageExtensions = setOf("bmp", "dng", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
    private val videoExtensions = setOf("3g2", "3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")
    private val passwordExportMarkers = listOf(
        "/password/",
        "/passwords/",
        "/password_exports/",
        "/password-exports/",
        "/credential/",
        "/credentials/",
        "/credential-backups/",
        "/browser-data/",
        "keepass",
        "bitwarden",
        "1password",
        "lastpass",
        "dashlane",
        "protonpass",
        "enpass",
        "passwordsafe",
        "password-vault",
        "credential-backup",
        "credential_store",
        "keychain",
    )
    private val portableCredentialFileNames = setOf(
        "login data",
        "logins.json",
        "key3.db",
        "key4.db",
        "signons.sqlite",
        "passwords.csv",
        "passwords.json",
        "credentials.csv",
        "credentials.json",
    )

    fun classify(path: String): ConsentCategory {
        val normalizedPath = path.replace('\\', '/').lowercase()
        val extension = normalizedPath.substringAfterLast('.', missingDelimiterValue = "")
        val fileName = normalizedPath.substringAfterLast('/')

        return when {
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
            isSystemInformation(normalizedPath, fileName) -> ConsentCategory.SYSTEM_INFORMATION
            isLog(normalizedPath, fileName) -> ConsentCategory.LOGS
            isRegistryHive(normalizedPath, fileName) -> ConsentCategory.CONFIGURATION
            isConfiguration(normalizedPath, fileName) -> ConsentCategory.CONFIGURATION
            isApplicationData(normalizedPath, extension) -> ConsentCategory.APPLICATION_DATA
            else -> ConsentCategory.DOCUMENTS
        }
    }

    private fun isSystemInformation(path: String, fileName: String): Boolean {
        val systemMarker = listOf(
            "/system-info/",
            "/system_information/",
            "/device-info/",
            "/diagnostics/",
            "system-info",
            "system_information",
            "device-info",
            "device_information",
            "bugreport",
            "build.prop",
        ).any { it in path || it in fileName }
        return systemMarker && fileName.substringAfterLast('.', "") in setOf(
            "json",
            "xml",
            "csv",
            "txt",
            "html",
            "prop",
            "zip",
        )
    }

    private fun isLog(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        val logMarker = "/log/" in path || "/logs/" in path || "/winevt/logs/" in path ||
            "crash" in fileName || "tombstone" in fileName || "diagnostic" in fileName
        return extension in setOf("log", "evtx", "etl") ||
            (logMarker && extension in setOf("txt", "json", "xml", "zip", "dmp"))
    }

    private fun isRegistryHive(path: String, fileName: String): Boolean {
        val registryPath = "/system32/config/" in path || "/registry/" in path
        return registryPath || fileName in setOf(
            "sam",
            "security",
            "software",
            "system",
            "default",
            "ntuser.dat",
            "usrclass.dat",
        )
    }

    private fun isConfiguration(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        val configurationPath = listOf("/config/", "/configs/", "/configuration/", "/settings/")
            .any(path::contains)
        return extension in setOf("cfg", "conf", "config", "ini", "plist", "properties", "toml", "yaml", "yml") ||
            (configurationPath && extension in setOf("json", "xml", "txt", "zip"))
    }

    private fun isApplicationData(path: String, extension: String): Boolean {
        val applicationPath = listOf(
            "/android/data/",
            "/application-data/",
            "/application_data/",
            "/app-data/",
            "/app_data/",
            "/apps/",
        ).any(path::contains)
        return applicationPath || extension in setOf("db", "sqlite", "sqlite3")
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
        if (fileName in portableCredentialFileNames) return true
        if (extension in setOf("kdb", "kdbx", "psafe3", "enpassbackup", "1pux", "crd", "vcrd", "keychain-db")) {
            return true
        }
        val passwordExportName = passwordExportMarkers.any { it in path || it in fileName }
        return passwordExportName && extension in setOf(
            "json",
            "csv",
            "xml",
            "zip",
            "bak",
            "backup",
            "db",
            "sqlite",
            "sqlite3",
            "plist",
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
        val extension = fileName.substringAfterLast('.', "")
        val chatName = listOf("whatsapp", "signal", "telegram", "messenger", "chat", "conversation")
            .any { it in path || it in fileName }
        val explicitExportLocation = listOf("/export", "/backup", "/download", "/phone sync/")
            .any(path::contains)
        if ("whatsapp" in path && extension.startsWith("crypt")) return true
        if ("signal" in path && extension == "backup") return true
        return chatName && extension in setOf("txt", "json", "html", "xml", "zip", "csv", "db") &&
            (extension != "db" || explicitExportLocation)
    }

    private fun isEmailExport(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        if (extension in setOf("eml", "mbox", "msg", "ost", "pst")) return true
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