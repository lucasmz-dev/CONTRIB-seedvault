/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.notification

import android.app.backup.BackupProgress
import android.app.backup.BackupTransport.AGENT_ERROR
import android.app.backup.IBackupObserver
import android.content.Context
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Looper
import android.util.Log
import android.util.Log.INFO
import android.util.Log.isLoggable
import com.stevesoltys.seedvault.ERROR_BACKUP_CANCELLED
import com.stevesoltys.seedvault.ERROR_BACKUP_NOT_ALLOWED
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageState.NOT_ALLOWED
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.repo.hexFromProto
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.worker.AppBackupPruneWorker
import com.stevesoltys.seedvault.worker.BackupRequester
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val TAG = NotificationBackupObserver::class.java.simpleName

internal class NotificationBackupObserver(
    private val context: Context,
    private val backupRequester: BackupRequester,
    private val requestedPackages: Int,
) : IBackupObserver.Stub(), KoinComponent {

    private val nm: BackupNotificationManager by inject()
    private val packageService: PackageService by inject()
    private val settingsManager: SettingsManager by inject()
    private val metadataManager: MetadataManager by inject()
    private val appBackupManager: AppBackupManager by inject()
    private val backendManager: BackendManager by inject()
    private var currentPackage: String? = null
    private var numPackages: Int = 0
    private var numPackagesToReport: Int = 0
    private var pmCounted: Boolean = false

    private var errorPackageName: String? = null
    private val launchableSystemApps by lazy {
        packageService.launchableSystemApps.map { it.activityInfo.packageName }.toSet()
    }

    init {
        // Inform the notification manager that a backup has started
        // and inform about the expected numbers of apps.
        nm.onBackupStarted(requestedPackages)
    }

    /**
     * This method could be called several times for packages with full data backup.
     * It will tell how much of backup data is already saved and how much is expected.
     *
     * Note that this will not be called for [MAGIC_PACKAGE_MANAGER]
     * which is usually the first package to get backed up.
     *
     * @param currentBackupPackage The name of the package that now being backed up.
     * @param backupProgress Current progress of backup for the package.
     */
    override fun onUpdate(currentBackupPackage: String?, backupProgress: BackupProgress) {
        showProgressNotification(currentBackupPackage)
    }

    /**
     * Backup of one package or initialization of one transport has completed.  This
     * method will be called at most one time for each package or transport, and might not
     * be not called if the operation fails before backupFinished(); for example, if the
     * requested package/transport does not exist.
     *
     * @param target The name of the package that was backed up, or of the transport
     *                  that was initialized
     * @param status Zero on success; a nonzero error code if the backup operation failed.
     */
    override fun onResult(target: String, status: Int) {
        if (isLoggable(TAG, INFO)) {
            Log.i(TAG, "Completed. Target: $target, status: $status")
        }
        // prevent double counting of @pm@ which gets backed up with each requested chunk
        if (target == MAGIC_PACKAGE_MANAGER) {
            if (!pmCounted) {
                numPackages += 1
                pmCounted = true
            }
        } else {
            numPackages += 1
        }
        // count package if success and not a system app
        if (status == 0 && target != MAGIC_PACKAGE_MANAGER) try {
            val appInfo = context.packageManager.getApplicationInfo(target, 0)
            // exclude system apps from final count for now
            if (appInfo.flags and FLAG_SYSTEM == 0) {
                numPackagesToReport += 1
            }
        } catch (e: Exception) {
            // should only happen for MAGIC_PACKAGE_MANAGER, but better save than sorry
            Log.e(TAG, "Error getting ApplicationInfo: ", e)
        }
        // record status for not-allowed apps visible in UI
        if (status == ERROR_BACKUP_NOT_ALLOWED) {
            // this should only ever happen for system apps, as we use only d2d now
            if (target in launchableSystemApps) {
                val packageInfo = context.packageManager.getPackageInfo(target, 0)
                 metadataManager.onPackageBackupError(packageInfo, NOT_ALLOWED)
            }
        }

        // Apps that get killed while interacting with their [BackupAgent] cancel the entire backup.
        // In order to prevent them from DoSing us, we remember them here to auto-disable them.
        // We noticed that the same app behavior can cause a status of
        // either AGENT_ERROR or ERROR_BACKUP_CANCELLED, so we need to handle both.
        errorPackageName = if (status == AGENT_ERROR || status == ERROR_BACKUP_CANCELLED) {
            target
        } else {
            null // To not disable apps by mistake, we reset it when getting a new non-error result.
        }

        // often [onResult] gets called right away without any [onUpdate] call
        showProgressNotification(target)
    }

    /**
     * The backup process has completed.  This method will always be called,
     * even if no individual package backup operations were attempted.
     *
     * @param status Zero on success; a nonzero error code if the backup operation
     *   as a whole failed.
     */
    override fun backupFinished(status: Int) {
        if (status == ERROR_BACKUP_CANCELLED) {
            val packageName = errorPackageName
            if (packageName == null) {
                Log.e(TAG, "Backup got cancelled, but there we have no culprit :(")
            } else {
                Log.w(TAG, "App $packageName misbehaved, will disable backup for it...")
                settingsManager.disableBackup(packageName)
            }
        }
        Log.i(TAG, "Backup finished $numPackages/$requestedPackages. Status: $status")
        if (backupRequester.hasNext && !backendManager.canDoBackupNow()) {
            Log.w(TAG, "Not requesting another backup, likely on metered network. ")
            nm.onBackupError(true)
        } else if (backupRequester.requestNext()) {
            // FIXME we should consider not requesting backup of more chunks of packages,
            //  if the backup has already failed for this chunk,
            //  because it will result in incomplete snapshots
            //  since the rest of packages from the failed chunk won't get backed up.
            //  So we either re-include those packages somehow (may fail again in a loop!)
            //  or we simply fail the entire backup which may cause more failures for users :(
            var success = status == 0
            val total = try {
                packageService.allUserPackages.size
            } catch (e: Exception) {
                Log.e(TAG, "Error getting number of all user packages: ", e)
                requestedPackages
            }
            val snapshot = runBlocking {
                check(!Looper.getMainLooper().isCurrentThread)
                Log.d(TAG, "Finalizing backup...")
                val snapshot = appBackupManager.afterBackupFinished(success)
                success = snapshot != null
                snapshot
            }
            val size = if (snapshot != null) { // TODO for later: count size of APKs separately
                val chunkIds = snapshot.appsMap.values.flatMap { it.chunkIdsList }
                chunkIds.sumOf {
                    snapshot.blobsMap[it.hexFromProto()]?.uncompressedLength?.toLong() ?: 0L
                }
            } else 0L
            if (success) {
                nm.onBackupSuccess(numPackagesToReport, total, size)
                // prune old backups
                AppBackupPruneWorker.scheduleNow(context)
            } else {
                nm.onBackupError()
            }
        }
    }

    private fun showProgressNotification(packageName: String?) {
        if (packageName == null || currentPackage == packageName) return

        if (isLoggable(TAG, INFO)) Log.i(
            TAG, "Showing progress notification for " +
                "$currentPackage $numPackages/$requestedPackages"
        )
        currentPackage = packageName
        val appName = getAppName(packageName)
        val name = if (appName != packageName) {
            appName
        } else {
            context.getString(R.string.backup_section_system)
        }
        Log.i(TAG, "$numPackages/$requestedPackages - $appName ($packageName)")
        nm.onBackupUpdate(name, numPackages, requestedPackages)
    }

    private fun getAppName(packageId: String): CharSequence = getAppName(context, packageId)

}

fun getAppName(
    context: Context,
    packageName: String,
    fallback: String = packageName,
): CharSequence {
    if (packageName == MAGIC_PACKAGE_MANAGER || packageName.startsWith("@")) {
        return context.getString(R.string.restore_magic_package)
    }
    return try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo)
    } catch (e: NameNotFoundException) {
        fallback
    }
}
