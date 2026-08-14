package com.jerrywolff.phonesyncusbc.domain

object TransferClassifier {
    private val imageExtensions = setOf("bmp", "dng", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
    private val videoExtensions = setOf("3g2", "3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")

    fun classify(path: String): ConsentCategory {
        val normalizedPath = path.replace('\\', '/').lowercase()
        val extension = normalizedPath.substringAfterLast('.', missingDelimiterValue = "")
        val fileName = normalizedPath.substringAfterLast('/')

        return when {
            isProtectedPrivateDatabase(normalizedPath) -> ConsentCategory.DOCUMENTS
            isSmsExport(normalizedPath, fileName) -> ConsentCategory.SMS_EXPORTS
            isChatExport(normalizedPath, fileName) -> ConsentCategory.CHAT_EXPORTS
            isEmailExport(normalizedPath, fileName) -> ConsentCategory.EMAIL_EXPORTS
            isNotificationExport(normalizedPath, fileName) -> ConsentCategory.NOTIFICATION_EXPORTS
            extension == "vcf" || extension == "vcard" -> ConsentCategory.CONTACTS
            extension in imageExtensions || extension in videoExtensions -> ConsentCategory.PHOTOS_AND_VIDEOS
            else -> ConsentCategory.DOCUMENTS
        }
    }

    private fun isSmsExport(path: String, fileName: String): Boolean {
        return ("/sms/" in path || "sms-export" in path || "sms_backup" in path || "smsbackup" in path) &&
            fileName.substringAfterLast('.', "") in setOf("xml", "json", "csv", "txt", "html", "zip")
    }

    private fun isChatExport(path: String, fileName: String): Boolean {
        val chatName = listOf("whatsapp", "signal", "telegram", "messenger", "chat", "conversation")
            .any { it in path || it in fileName }
        return chatName && fileName.substringAfterLast('.', "") in setOf("txt", "json", "html", "xml", "zip", "csv", "db")
    }

    private fun isEmailExport(path: String, fileName: String): Boolean {
        val mailName = listOf("email", "mail", "gmail", "outlook", "thunderbird", "inbox", "sent")
            .any { it in path || it in fileName }
        return mailName && fileName.substringAfterLast('.', "") in setOf("eml", "mbox", "msg", "pst", "txt", "html", "json", "xml", "zip")
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
        return privateLocation && extension in setOf("db", "sqlite", "sqlite3")
    }
}