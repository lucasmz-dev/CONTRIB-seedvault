/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.ui.check

import android.app.NotificationManager
import android.os.Bundle
import android.text.format.Formatter.formatShortFileSize
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import org.calyxos.backup.storage.R
import org.calyxos.backup.storage.api.CheckResult
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.ui.Notifications.Companion.onCheckCompleteNotificationSeen
import org.calyxos.backup.storage.ui.restore.SnapshotAdapter
import org.calyxos.backup.storage.ui.restore.SnapshotClickListener

internal const val ACTION_FINISHED = "FINISHED"
internal const val ACTION_SHOW = "SHOW"
private val TAG = CheckResultActivity::class.simpleName

public abstract class CheckResultActivity : AppCompatActivity(), SnapshotClickListener {

    protected abstract val storageBackup: StorageBackup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check_results)

        if (savedInstanceState == null) when (intent.action) {
            ACTION_FINISHED -> {
                val nm = getSystemService(NotificationManager::class.java)
                onCheckCompleteNotificationSeen(nm)
                finish() // result will be cleared in onDestroy()
            }
            ACTION_SHOW -> {
                val nm = getSystemService(NotificationManager::class.java)
                onCheckCompleteNotificationSeen(nm)
                onActionReceived()
            }
            else -> {
                Log.e(TAG, "Unknown action: ${intent.action}")
                finish()
            }
        }
    }

    private fun onActionReceived() {
        when (val result = storageBackup.checkResult) {
            is CheckResult.Success -> onSuccess(result)
            is CheckResult.Error -> onError(result)
            is CheckResult.GeneralError, null -> {
                if (result == null) {
                    val str = getString(R.string.check_error_no_result)
                    val e = NullPointerException(str)
                    val r = CheckResult.GeneralError(e)
                    onGeneralError(r)
                } else {
                    onGeneralError(result as CheckResult.GeneralError)
                }
            }
        }
    }

    private fun onSuccess(result: CheckResult.Success) {
        setContentView(R.layout.activity_check_results)
        val intro = getString(
            R.string.check_success_intro,
            result.snapshots.size,
            result.percent,
            formatShortFileSize(this, result.size),
        )
        requireViewById<TextView>(R.id.introView).text = intro

        val items = result.snapshots.map { snapshot ->
            SnapshotItem(
                storedSnapshot = StoredSnapshot("doesNotMatter", snapshot.timeStart),
                snapshot = snapshot,
            )
        }.sortedByDescending { it.time }
        val listView = requireViewById<RecyclerView>(R.id.listView)
        listView.adapter = SnapshotAdapter(this).apply {
            submitList(items)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) storageBackup.clearCheckResult()
    }

    private fun onError(result: CheckResult.Error) {
        setContentView(R.layout.activity_check_results)
        requireViewById<ImageView>(R.id.imageView).setImageResource(R.drawable.ic_cloud_error)
        requireViewById<TextView>(R.id.titleView).setText(R.string.check_error_title)

        val intro = if (result.existingSnapshots == 0) {
            getString(R.string.check_error_no_snapshots)
        } else if (result.snapshots.isEmpty()) {
            getString(
                R.string.check_error_only_broken_snapshots,
                result.existingSnapshots,
            )
        } else {
            getString(R.string.check_error_has_snapshots, result.existingSnapshots)
        }
        requireViewById<TextView>(R.id.introView).text = intro

        val items = (result.goodSnapshots.map { snapshot ->
            SnapshotItem(
                storedSnapshot = StoredSnapshot("doesNotMatter", snapshot.timeStart),
                snapshot = snapshot,
                hasError = false,
            )
        } + result.badSnapshots.map { snapshot ->
            SnapshotItem(
                storedSnapshot = StoredSnapshot("doesNotMatter", snapshot.timeStart),
                snapshot = snapshot,
                hasError = true,
            )
        }).sortedByDescending { it.time }
        val listView = requireViewById<RecyclerView>(R.id.listView)
        listView.adapter = SnapshotAdapter(this).apply {
            submitList(items)
        }
    }

    private fun onGeneralError(result: CheckResult.GeneralError) {
        setContentView(R.layout.activity_check_results)
        requireViewById<ImageView>(R.id.imageView).setImageResource(R.drawable.ic_cloud_error)
        requireViewById<TextView>(R.id.titleView).setText(R.string.check_error_title)

        requireViewById<TextView>(R.id.introView).text =
            getString(R.string.check_error_no_snapshots)

        requireViewById<TextView>(R.id.errorView).text =
            "${result.e.localizedMessage}\n\n${result.e}"
    }

    override fun onSnapshotClicked(item: SnapshotItem) {
        Toast.makeText(this, "Not yet implemented", Toast.LENGTH_SHORT).show()
    }

}
