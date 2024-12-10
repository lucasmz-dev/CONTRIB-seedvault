/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat.getColor
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.AppBackupState.FAILED_NOT_INSTALLED
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class RestoreProgressFragment : Fragment() {

    private val viewModel: RestoreViewModel by activityViewModel()

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = RestoreProgressAdapter(lifecycleScope, this::loadIcon)

    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var backupNameView: TextView
    private lateinit var appList: RecyclerView
    private lateinit var button: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_restore_progress, container, false)

        progressBar = v.requireViewById(R.id.progressBar)
        titleView = v.requireViewById(R.id.titleView)
        backupNameView = v.requireViewById(R.id.backupNameView)
        appList = v.requireViewById(R.id.appList)
        button = v.requireViewById(R.id.button)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        titleView.setText(R.string.restore_restoring)

        appList.apply {
            layoutManager = this@RestoreProgressFragment.layoutManager
            adapter = this@RestoreProgressFragment.adapter
        }

        button.setText(R.string.restore_finished_button)
        button.setOnClickListener {
            viewModel.onFinishClickedAfterRestoringAppData()
        }

        // decryption will fail when the device is locked, so keep the screen on to prevent locking
        requireActivity().window.addFlags(FLAG_KEEP_SCREEN_ON)

        viewModel.chosenRestorableBackup.observe(viewLifecycleOwner) { restorableBackup ->
            backupNameView.text = restorableBackup.name
            progressBar.max = restorableBackup.packageMetadataMap.size
        }

        viewModel.restoreProgress.observe(viewLifecycleOwner) { list ->
            progressBar.progress = list.size
            val position = layoutManager.findFirstVisibleItemPosition()
            adapter.update(list) {
                if (position == 0) layoutManager.scrollToPosition(0)
            }
        }

        viewModel.restoreBackupResult.observe(viewLifecycleOwner) { finished ->
            button.isEnabled = true
            if (finished.hasError()) {
                backupNameView.text = finished.errorMsg
                backupNameView.setTextColor(getColor(requireContext(), R.color.red))
            } else {
                backupNameView.text = getString(R.string.restore_finished_success)
                onRestoreFinished()
            }
            activity?.window?.clearFlags(FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun onRestoreFinished() {
        // check if any restore failed, because the app is not installed
        val failed = viewModel.restoreProgress.value?.any { it.state == FAILED_NOT_INSTALLED }
        if (failed != true) return // nothing left to do if there's no failures due to not installed
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_restoring_error_title)
            .setMessage(R.string.restore_restoring_error_message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private suspend fun loadIcon(item: AppRestoreResult, callback: (Drawable) -> Unit) {
        viewModel.loadIcon(item.packageName, callback)
    }

}
