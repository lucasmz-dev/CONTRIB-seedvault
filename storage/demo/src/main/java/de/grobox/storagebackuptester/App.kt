/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester

import android.app.Application
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.util.Log
import com.google.android.material.color.DynamicColors
import de.grobox.storagebackuptester.crypto.KeyManager
import de.grobox.storagebackuptester.plugin.TestSafBackend
import de.grobox.storagebackuptester.settings.SettingsManager
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.ui.restore.FileSelectionManager

class App : Application() {

    val settingsManager: SettingsManager by lazy { SettingsManager(applicationContext) }
    val storageBackup: StorageBackup by lazy {
        val plugin = TestSafBackend(this) { settingsManager.getBackupLocation() }
        StorageBackup(this, { plugin }, KeyManager)
    }
    val fileSelectionManager: FileSelectionManager get() = FileSelectionManager()

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.e("TEST", "ON LOW MEMORY!!!")
    }

}
