package com.jerrywolff.phonesyncusbc

import android.app.Application
import com.jerrywolff.phonesyncusbc.data.AuditLog
import com.jerrywolff.phonesyncusbc.data.DataExportManager
import com.jerrywolff.phonesyncusbc.data.DeviceKeyManager
import com.jerrywolff.phonesyncusbc.data.TrustStore
import com.jerrywolff.phonesyncusbc.sync.MtpSyncEngine
import com.jerrywolff.phonesyncusbc.usb.UsbSourceResolver

class PhoneSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val exportManager = DataExportManager(this)
        val preferences = getSharedPreferences(MAINTENANCE_PREFERENCES, MODE_PRIVATE)
        if (!preferences.getBoolean(OBSOLETE_ARCHIVES_CLEANED, false)) {
            exportManager.cleanupObsoleteUploadArchives()
            preferences.edit().putBoolean(OBSOLETE_ARCHIVES_CLEANED, true).apply()
        } else {
            exportManager.cleanupInterruptedUploadArchives()
        }
    }

    val keyManager: DeviceKeyManager by lazy { DeviceKeyManager() }
    val trustStore: TrustStore by lazy { TrustStore(this, keyManager) }
    val auditLog: AuditLog by lazy { AuditLog(this) }
    val usbSourceResolver: UsbSourceResolver by lazy { UsbSourceResolver(this) }
    val mtpSyncEngine: MtpSyncEngine by lazy {
        MtpSyncEngine(this, auditLog, usbSourceResolver)
    }

    private companion object {
        const val MAINTENANCE_PREFERENCES = "phone_sync_maintenance"
        const val OBSOLETE_ARCHIVES_CLEANED = "obsolete_upload_archives_cleaned_v211"
    }
}