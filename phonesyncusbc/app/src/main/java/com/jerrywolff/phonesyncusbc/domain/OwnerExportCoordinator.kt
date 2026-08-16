package com.jerrywolff.phonesyncusbc.domain

data class OwnerExportAction(
    val service: String,
    val category: ConsentCategory,
    val ownerSteps: String,
    val expectedArtifacts: String,
)

data class OwnerExportWorkflow(
    val sourcePlatform: SourcePlatform,
    val actions: List<OwnerExportAction>,
) {
    val requiresOwnerAction: Boolean = actions.isNotEmpty()
}

object OwnerExportCoordinator {
    fun trigger(
        sourcePlatform: SourcePlatform,
        visibleCategories: Set<ConsentCategory>,
    ): OwnerExportWorkflow {
        val missingCategories = SourceExportRequirements.missingFrom(visibleCategories).toSet()
        val actions = buildList {
            if (ConsentCategory.CHAT_EXPORTS in missingCategories) addAll(chatExportActions(sourcePlatform))
            if (ConsentCategory.SMS_EXPORTS in missingCategories) {
                add(
                    OwnerExportAction(
                        service = "SMS/MMS app",
                        category = ConsentCategory.SMS_EXPORTS,
                        ownerSteps = sourceAction(
                            sourcePlatform,
                            "use the messaging app's backup/export command and save an XML, JSON, CSV, text, or ZIP export",
                        ),
                        expectedArtifacts = "SMS/MMS export (.xml, .json, .csv, .txt, or .zip)",
                    ),
                )
            }
            if (ConsentCategory.CALL_LOGS in missingCategories) {
                add(
                    OwnerExportAction(
                        service = "Phone / call-history app",
                        category = ConsentCategory.CALL_LOGS,
                        ownerSteps = sourceAction(
                            sourcePlatform,
                            "use its supported call-history export or account backup and place the exported file in shared storage",
                        ),
                        expectedArtifacts = "Call-history export (.json, .xml, .csv, .txt, or .zip)",
                    ),
                )
            }
            if (ConsentCategory.EMAIL_EXPORTS in missingCategories) {
                add(
                    OwnerExportAction(
                        service = "Mail provider / email app",
                        category = ConsentCategory.EMAIL_EXPORTS,
                        ownerSteps = sourceAction(
                            sourcePlatform,
                            "use the provider's account export or the app's Save/Export command, then download the result to shared storage",
                        ),
                        expectedArtifacts = "Mail export (.eml, .mbox, .msg, .pst, .ost, or .zip)",
                    ),
                )
            }
        }
        return OwnerExportWorkflow(sourcePlatform, actions)
    }

    private fun chatExportActions(sourcePlatform: SourcePlatform): List<OwnerExportAction> {
        return CHAT_SERVICES.map { service ->
            OwnerExportAction(
                service = service.name,
                category = ConsentCategory.CHAT_EXPORTS,
                ownerSteps = sourceAction(sourcePlatform, service.action),
                expectedArtifacts = service.expectedArtifacts,
            )
        } + OwnerExportAction(
            service = "Other messaging or meeting app",
            category = ConsentCategory.CHAT_EXPORTS,
            ownerSteps = sourceAction(
                sourcePlatform,
                "open its Privacy, Data, Chat, Meeting, or Account settings; choose Export, Download, Save transcript, or Request data",
            ),
            expectedArtifacts = "Owner-created chat export, transcript, recording, attachment archive, or account-data ZIP",
        )
    }

    private fun sourceAction(sourcePlatform: SourcePlatform, action: String): String {
        val destination = when (sourcePlatform) {
            SourcePlatform.ANDROID -> "Save it under Downloads or another MTP-visible shared folder."
            SourcePlatform.IOS -> "Save it to Files or the provider cloud, then download/copy it to USB-visible storage; iPhone PTP itself exposes only media."
            SourcePlatform.WINDOWS_PHONE -> "Save it to a shared MTP-visible folder or provider storage."
            SourcePlatform.UNKNOWN -> "Save it to storage that this device exposes over USB."
        }
        return "On the external source device, $action. $destination"
    }

    private data class ChatService(
        val name: String,
        val action: String,
        val expectedArtifacts: String,
    )

    private val CHAT_SERVICES = listOf(
        ChatService(
            "WhatsApp",
            "open each required chat, choose More > Export chat, and include media when needed",
            "WhatsApp chat text/ZIP plus optional media",
        ),
        ChatService(
            "Microsoft Teams",
            "save available meeting transcripts/recordings and use the account or organization export process for chat data",
            "Teams messages/account export, transcript, recording, or attachments",
        ),
        ChatService(
            "Zoom Workplace",
            "save meeting chat and transcript files, and download cloud recordings/transcripts from the owner account",
            "Zoom meeting chat, transcript (.vtt/.txt), recording, or account export",
        ),
        ChatService(
            "Cisco Webex",
            "download meeting transcripts, chats, recordings, and any available account-data export",
            "Webex transcript, chat, recording, or account export",
        ),
        ChatService(
            "Signal",
            "use Signal's supported transfer/backup flow where available; do not copy its private database",
            "Signal-supported backup or owner-created chat export",
        ),
        ChatService(
            "Telegram",
            "use Telegram Desktop's Export Telegram data command for the required chats and media",
            "Telegram HTML/JSON export and media folders",
        ),
        ChatService(
            "Slack",
            "request or download the workspace export permitted for the owner's role",
            "Slack workspace/account export ZIP",
        ),
        ChatService(
            "Google Chat",
            "request the owner's Google account data export with Chat selected",
            "Google Chat account export archive",
        ),
        ChatService(
            "Discord / Messenger",
            "request the owner's account data package from the service's Privacy or Account settings",
            "Account data ZIP containing messages and attachments",
        ),
    )
}