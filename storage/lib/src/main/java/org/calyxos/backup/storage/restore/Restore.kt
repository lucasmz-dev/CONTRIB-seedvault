/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.restore

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.calyxos.backup.storage.SnapshotRetriever
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.api.SnapshotResult
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.backup.Backup
import org.calyxos.backup.storage.backup.BackupSnapshot
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.getBackupSnapshotsForRestore
import org.calyxos.backup.storage.measure
import org.calyxos.seedvault.core.backends.IBackendManager
import org.calyxos.seedvault.core.crypto.KeyManager
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException

private const val TAG = "Restore"

internal class Restore(
    context: Context,
    private val backendManager: IBackendManager,
    private val keyManager: KeyManager,
    private val snapshotRetriever: SnapshotRetriever,
    fileRestore: FileRestore,
    streamCrypto: StreamCrypto = StreamCrypto,
) {

    private val backend get() = backendManager.backend
    private val streamKey by lazy {
        // This class might get instantiated before the Backend had time to provide the key
        // so we need to get it lazily here to prevent crashes. We can still crash later,
        // if the plugin is not providing a key as it should when performing calls into this class.
        try {
            streamCrypto.deriveStreamKey(keyManager.getMainKey())
        } catch (e: GeneralSecurityException) {
            throw AssertionError(e)
        }
    }

    // lazily instantiate these, so they don't try to get the streamKey too early
    private val zipChunkRestore by lazy {
        ZipChunkRestore(backendManager, fileRestore, streamCrypto, streamKey)
    }
    private val singleChunkRestore by lazy {
        SingleChunkRestore(backendManager, fileRestore, streamCrypto, streamKey)
    }
    private val multiChunkRestore by lazy {
        MultiChunkRestore(context, backendManager, fileRestore, streamCrypto, streamKey)
    }

    fun getBackupSnapshots(): Flow<SnapshotResult> = flow {
        val numSnapshots: Int
        val time = measure {
            val list = try {
                // get all available backups, they may not be usable
                backend.getBackupSnapshotsForRestore().sortedByDescending { storedSnapshot ->
                    storedSnapshot.timestamp
                }.map { storedSnapshot ->
                    // as long as snapshot is null, it can't be used for restore
                    SnapshotItem(storedSnapshot, null)
                }.toMutableList()
            } catch (e: Exception) {
                Log.e("TAG", "Error retrieving snapshots", e)
                emit(SnapshotResult.Error(e))
                return@flow
            }
            // return list copies, so this works with ListAdapter and DiffUtils
            emit(SnapshotResult.Success(ArrayList(list)))
            // try to decrypt snapshots and replace list items, if we can decrypt, otherwise remove
            numSnapshots = list.size
            val iterator = list.listIterator()
            while (iterator.hasNext()) {
                val oldItem = iterator.next()
                val item = try {
                    oldItem.copy(
                        snapshot = snapshotRetriever.getSnapshot(streamKey, oldItem.storedSnapshot)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error retrieving snapshot ${oldItem.time}", e)
                    null
                }
                if (item == null) {
                    iterator.remove() // remove the failing item from the list
                } else {
                    iterator.set(item) // replace old item with new item
                }
                emit(SnapshotResult.Success(ArrayList(list)))
            }
        }
        Log.e(TAG, "Decrypting and parsing $numSnapshots snapshots took $time")
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    suspend fun restoreBackupSnapshot(
        storedSnapshot: StoredSnapshot,
        snapshot: BackupSnapshot,
        observer: RestoreObserver? = null,
    ) {
        val filesTotal = snapshot.mediaFilesList.size + snapshot.documentFilesList.size
        val totalSize =
            snapshot.mediaFilesList.sumOf { it.size } + snapshot.documentFilesList.sumOf { it.size }
        observer?.onRestoreStart(filesTotal, totalSize)

        val split = FileSplitter.splitSnapshot(snapshot)
        observer?.onFileDuplicatesRemoved(split.numRemovedDuplicates)
        var restoredFiles = split.numRemovedDuplicates // count removed dups, so numbers add up

        val version = snapshot.version
        val smallFilesDuration = measure {
            restoredFiles += zipChunkRestore.restore(
                version,
                storedSnapshot,
                split.zipChunks,
                observer,
            )
        }
        Log.e(TAG, "Restoring ${split.zipChunks.size} zip chunks took $smallFilesDuration.")
        val singleChunkDuration = measure {
            restoredFiles += singleChunkRestore.restore(
                version,
                storedSnapshot,
                split.singleChunks,
                observer,
            )
        }
        Log.e(TAG, "Restoring ${split.singleChunks.size} single chunks took $singleChunkDuration.")
        val multiChunkDuration = measure {
            restoredFiles += multiChunkRestore.restore(
                version,
                storedSnapshot,
                split.multiChunkMap,
                split.multiChunkFiles,
                observer,
            )
        }
        Log.e(TAG, "Restoring ${split.multiChunkFiles.size} multi chunks took $multiChunkDuration.")

        val totalDuration = smallFilesDuration + singleChunkDuration + multiChunkDuration
        observer?.onRestoreComplete(totalDuration.inWholeMilliseconds)
        Log.e(TAG, "Restored $restoredFiles/$filesTotal files.")
    }

}

@Throws(IOException::class, GeneralSecurityException::class)
internal fun InputStream.readVersion(expectedVersion: Int? = null): Int {
    val version = read()
    if (version == -1) throw FileEmptyException()
    if (expectedVersion != null && version != expectedVersion) {
        throw GeneralSecurityException("Expected version $expectedVersion, not $version")
    }
    if (version > Backup.VERSION) throw UnsupportedVersionException(
        "Got version $version which is higher than what is supported."
    )
    return version
}

internal class FileEmptyException : IOException("File empty!")
internal class UnsupportedVersionException(msg: String) : IOException(msg)
