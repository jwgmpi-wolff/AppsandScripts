package com.jerrywolff.phonesyncusbc.domain

enum class AccessMode {
    MTP,
    PTP,
    PORTABLE_EXPORT,
    SAF,
    PROVIDER_AUTHORIZATION,
}

data class CategoryCapability(
    val category: ConsentCategory,
    val accessMode: AccessMode,
    val description: String,
)

enum class ProtectedSurface {
    SMS_DATABASE,
    CHAT_APPLICATION_DATABASE,
    EMAIL_APPLICATION_DATABASE,
    OTHER_APP_PRIVATE_STORAGE,
    LIVE_NOTIFICATION_CONTENT,
    ACCESSIBILITY_SCRAPING,
}

data class ProtectedSurfacePolicy(
    val surface: ProtectedSurface,
    val supported: Boolean,
    val alternative: String?,
)

data class SourceCapabilities(
    val physicalConnection: PhysicalConnection,
    val connectionSupported: Boolean,
    val connectionMessage: String,
    val categories: List<CategoryCapability>,
    val protectedSurfaces: List<ProtectedSurfacePolicy>,
) {
    val supportedCategories: Set<ConsentCategory> = categories.mapTo(linkedSetOf()) { it.category }
}

object SourceCapabilityPolicy {
    fun forSource(source: DetectedSource): SourceCapabilities {
        val mediaMode = when (source.platform) {
            SourcePlatform.IOS -> AccessMode.PTP
            SourcePlatform.ANDROID, SourcePlatform.WINDOWS_PHONE -> AccessMode.MTP
            SourcePlatform.UNKNOWN -> AccessMode.SAF
        }
        val documentMode = when (source.platform) {
            SourcePlatform.ANDROID, SourcePlatform.WINDOWS_PHONE -> AccessMode.MTP
            SourcePlatform.IOS -> AccessMode.PORTABLE_EXPORT
            SourcePlatform.UNKNOWN -> AccessMode.SAF
        }

        return SourceCapabilities(
            physicalConnection = source.connection,
            connectionSupported = source.connection != PhysicalConnection.FIREWIRE,
            connectionMessage = when (source.connection) {
                PhysicalConnection.FIREWIRE -> "FireWire is not exposed through Android USB host APIs. Use a USB or USB-C data connection."
                PhysicalConnection.USB_C -> "USB-C data connection detected"
                PhysicalConnection.USB_A -> "USB-A data connection detected"
                PhysicalConnection.USB -> "USB data connection detected"
                PhysicalConnection.UNKNOWN -> "USB data connection type is not reported by Android"
            },
            categories = listOf(
                CategoryCapability(
                    ConsentCategory.PHOTOS_AND_VIDEOS,
                    mediaMode,
                    if (mediaMode == AccessMode.PTP) {
                        "Photos and videos exposed by iPhone over PTP"
                    } else {
                        "Photos and videos exposed by the source phone"
                    },
                ),
                CategoryCapability(
                    ConsentCategory.DOCUMENTS,
                    documentMode,
                    if (documentMode == AccessMode.PORTABLE_EXPORT) {
                        "Documents explicitly exported from iOS apps"
                    } else {
                        "Documents exposed by the source phone"
                    },
                ),
                CategoryCapability(
                    ConsentCategory.CONTACTS,
                    AccessMode.PORTABLE_EXPORT,
                    "Contact files exported as vCard (.vcf)",
                ),
                CategoryCapability(
                    ConsentCategory.SELECTED_FOLDERS,
                    AccessMode.SAF,
                    "Folders selected with Android's system file picker",
                ),
                CategoryCapability(
                    ConsentCategory.CLOUD_ACCOUNTS,
                    AccessMode.PROVIDER_AUTHORIZATION,
                    "Provider folders approved through Android's system file picker or OAuth",
                ),
                CategoryCapability(
                    ConsentCategory.SMS_EXPORTS,
                    AccessMode.PORTABLE_EXPORT,
                    "User-created SMS export packages; never a private SMS database",
                ),
                CategoryCapability(
                    ConsentCategory.CHAT_EXPORTS,
                    AccessMode.PORTABLE_EXPORT,
                    "User-created chat exports from the source application",
                ),
                CategoryCapability(
                    ConsentCategory.EMAIL_EXPORTS,
                    AccessMode.PORTABLE_EXPORT,
                    "User-created mail exports such as .eml or .mbox files",
                ),
                CategoryCapability(
                    ConsentCategory.NOTIFICATION_EXPORTS,
                    AccessMode.PORTABLE_EXPORT,
                    "Notification history explicitly exported by a source companion",
                ),
            ),
            protectedSurfaces = protectedSurfacePolicies,
        )
    }

    private val DetectedSource.connection: PhysicalConnection
        get() = physicalConnection

    private val protectedSurfacePolicies = listOf(
        ProtectedSurfacePolicy(
            ProtectedSurface.SMS_DATABASE,
            supported = false,
            alternative = "Export SMS on the source phone and authorize SMS exports.",
        ),
        ProtectedSurfacePolicy(
            ProtectedSurface.CHAT_APPLICATION_DATABASE,
            supported = false,
            alternative = "Use the chat application's own export feature.",
        ),
        ProtectedSurfacePolicy(
            ProtectedSurface.EMAIL_APPLICATION_DATABASE,
            supported = false,
            alternative = "Export messages from the mail provider or app.",
        ),
        ProtectedSurfacePolicy(
            ProtectedSurface.OTHER_APP_PRIVATE_STORAGE,
            supported = false,
            alternative = "Use that application's supported export or backup flow.",
        ),
        ProtectedSurfacePolicy(
            ProtectedSurface.LIVE_NOTIFICATION_CONTENT,
            supported = false,
            alternative = "Use an explicit source-companion notification export.",
        ),
        ProtectedSurfacePolicy(
            ProtectedSurface.ACCESSIBILITY_SCRAPING,
            supported = false,
            alternative = null,
        ),
    )
}