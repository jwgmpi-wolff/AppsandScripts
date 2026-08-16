package com.jerrywolff.phonesyncusbc.domain

object SourceExportRequirements {
    val categories = listOf(
        ConsentCategory.SMS_EXPORTS,
        ConsentCategory.CHAT_EXPORTS,
        ConsentCategory.CALL_LOGS,
        ConsentCategory.EMAIL_EXPORTS,
    )

    fun missingFrom(visibleCategories: Set<ConsentCategory>): List<ConsentCategory> {
        return categories.filterNot { it in visibleCategories }
    }
}
