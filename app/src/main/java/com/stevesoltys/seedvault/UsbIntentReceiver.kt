/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED
import android.hardware.usb.UsbManager.EXTRA_DEVICE
import android.provider.DocumentsContract
import android.util.Log
import com.stevesoltys.seedvault.settings.FlashDrive
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_STORAGE
import org.koin.core.context.GlobalContext.get
import java.util.Date

private val TAG = UsbIntentReceiver::class.java.simpleName

/**
 * When we get the [ACTION_USB_DEVICE_ATTACHED] broadcast, the storage is not yet available.
 * So we need to use a ContentObserver inside [UsbMonitorService]
 * to request a backup only once available.
 * We can't use the ContentObserver here, because if we are not in foreground,
 * the system freezes/caches us and queues our broadcasts until we are in foreground again.
 */
class UsbIntentReceiver : BroadcastReceiver() {

    // using KoinComponent would crash robolectric tests :(
    private val settingsManager: SettingsManager by lazy { get().get() }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (intent.action == ACTION_USB_DEVICE_ATTACHED ||
            intent.action == ACTION_USB_DEVICE_DETACHED
        ) {
            val device = intent.extras?.getParcelable(EXTRA_DEVICE, UsbDevice::class.java) ?: return
            Log.d(TAG, "New USB mass-storage device attached.")
            device.log()

            if (needsBackup(action, device)) {
                val i = Intent(context, UsbMonitorService::class.java).apply {
                    data = DocumentsContract.buildRootsUri(AUTHORITY_STORAGE)
                }
                context.startForegroundService(i)
            }
        }
    }

    private fun needsBackup(action: String, device: UsbDevice): Boolean {
        if (action != ACTION_USB_DEVICE_ATTACHED) return false
        Log.d(TAG, "Checking if this is the current backup drive...")
        val savedFlashDrive = settingsManager.getFlashDrive() ?: return false
        val attachedFlashDrive = FlashDrive.from(device)
        return if (savedFlashDrive == attachedFlashDrive) {
            Log.d(TAG, "  Matches stored device, checking backup time...")
            val lastBackupTime = settingsManager.lastBackupTime.value ?: 0
            val backupMillis = System.currentTimeMillis() - lastBackupTime
            if (backupMillis >= settingsManager.backupFrequencyInMillis) {
                Log.d(TAG, "Last backup older than it should be, requesting a backup...")
                Log.d(TAG, "  ${Date(lastBackupTime)}")
                true
            } else {
                Log.d(TAG, "We have a recent backup, not requesting a new one.")
                Log.d(TAG, "  ${Date(lastBackupTime)}")
                false
            }
        } else {
            Log.d(TAG, "  Different device attached, ignoring...")
            false
        }
    }
}

internal fun UsbDevice.isMassStorage(): Boolean {
    for (i in 0 until interfaceCount) {
        if (getInterface(i).isMassStorage()) return true
    }
    return false
}

private fun UsbInterface.isMassStorage(): Boolean {
    @Suppress("MagicNumber")
    return interfaceClass == 8 && interfaceProtocol == 80 && interfaceSubclass == 6
}

private fun UsbDevice.log() {
    Log.d(TAG, "  name: $manufacturerName $productName")
    Log.d(TAG, "  serialNumber: $serialNumber")
    Log.d(TAG, "  productId: $productId")
    Log.d(TAG, "  vendorId: $vendorId")
    Log.d(TAG, "  isMassStorage: ${isMassStorage()}")
}
