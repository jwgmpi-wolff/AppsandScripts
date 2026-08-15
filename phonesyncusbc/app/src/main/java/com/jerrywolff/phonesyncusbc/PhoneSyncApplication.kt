package com.jerrywolff.phonesyncusbc

import android.app.Application
import com.jerrywolff.phonesyncusbc.data.AuditLog
import com.jerrywolff.phonesyncusbc.data.DeviceKeyManager
import com.jerrywolff.phonesyncusbc.data.TrustStore
import com.jerrywolff.phonesyncusbc.sync.MtpSyncEngine
import com.jerrywolff.phonesyncusbc.usb.UsbSourceResolver

class PhoneSyncApplication : Application() {
    val keyManager: DeviceKeyManager by lazy { DeviceKeyManager() }
    val trustStore: TrustStore by lazy { TrustStore(this, keyManager) }
    val auditLog: AuditLog by lazy { AuditLog(this) }
    val usbSourceResolver: UsbSourceResolver by lazy { UsbSourceResolver(this) }
    val mtpSyncEngine: MtpSyncEngine by lazy {
        MtpSyncEngine(this, auditLog, usbSourceResolver)
    }
}