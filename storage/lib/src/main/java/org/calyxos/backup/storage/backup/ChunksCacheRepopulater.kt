/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.backup

import android.util.Log
import org.calyxos.backup.storage.SnapshotRetriever
import org.calyxos.backup.storage.db.CachedChunk
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.getCurrentBackupSnapshots
import org.calyxos.backup.storage.measure
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileBackupFileType
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.toDuration

private const val TAG = "ChunksCacheRepopulater"

internal class ChunksCacheRepopulater(
    private val db: Db,
    private val storagePlugin: () -> Backend,
    private val androidId: String,
    private val snapshotRetriever: SnapshotRetriever,
) {

    suspend fun repopulate(streamKey: ByteArray, availableChunkIds: Map<String, Long>) {
        Log.i(TAG, "Starting to repopulate chunks cache")
        try {
            repopulateInternal(streamKey, availableChunkIds)
        } catch (e: Exception) {
            // TODO what can we do now? Just try again next time?
            Log.e(TAG, "Error while repopulating chunks cache", e)
        }
    }

    @Throws(IOException::class)
    private suspend fun repopulateInternal(
        streamKey: ByteArray,
        availableChunkIds: Map<String, Long>,
    ) {
        val start = System.currentTimeMillis()
        val snapshots =
            storagePlugin().getCurrentBackupSnapshots(androidId).mapNotNull { storedSnapshot ->
                try {
                    snapshotRetriever.getSnapshot(streamKey, storedSnapshot)
                } catch (e: GeneralSecurityException) {
                    Log.w(TAG, "Error fetching snapshot $storedSnapshot", e)
                    null
                }
            }
        val snapshotDuration = (System.currentTimeMillis() - start).toDuration(MILLISECONDS)
        Log.i(TAG, "Retrieving and parsing all snapshots took $snapshotDuration")

        val cachedChunks = getCachedChunks(snapshots, availableChunkIds)
        val repopulateDuration = measure {
            db.getChunksCache().clearAndRepopulate(db, cachedChunks)
        }
        Log.i(TAG, "Repopulating chunks cache took $repopulateDuration")

        // delete chunks that are not references by any snapshot anymore
        val chunksToDelete = availableChunkIds.keys.subtract(cachedChunks.map { it.id }.toSet())
        val deletionDuration = measure {
            chunksToDelete.forEach { chunkId ->
                val handle = FileBackupFileType.Blob(androidId, chunkId)
                storagePlugin().remove(handle)
            }
        }
        Log.i(TAG, "Deleting ${chunksToDelete.size} chunks took $deletionDuration")
    }

    private fun getCachedChunks(
        snapshots: List<BackupSnapshot>,
        availableChunks: Map<String, Long>,
    ): Collection<CachedChunk> {
        val chunkMap = HashMap<String, CachedChunk>()
        snapshots.forEach { snapshot ->
            val chunksInSnapshot = HashSet<String>()
            snapshot.mediaFilesList.forEach { file ->
                file.chunkIdsList.forEach { chunkId -> chunksInSnapshot.add(chunkId) }
            }
            snapshot.documentFilesList.forEach { file ->
                file.chunkIdsList.forEach { chunkId -> chunksInSnapshot.add(chunkId) }
            }
            addCachedChunksToMap(snapshot, availableChunks, chunkMap, chunksInSnapshot)
        }
        return chunkMap.values
    }

    private fun addCachedChunksToMap(
        snapshot: BackupSnapshot,
        availableChunks: Map<String, Long>,
        chunkMap: HashMap<String, CachedChunk>,
        chunksInSnapshot: HashSet<String>,
    ) = chunksInSnapshot.forEach { chunkId ->
        val size = availableChunks[chunkId]
        if (size == null) {
            Log.w(TAG, "ChunkId $chunkId referenced in ${snapshot.timeStart}, but not in storage.")
            return@forEach
        }
        val cachedChunk = chunkMap.getOrElse(chunkId) {
            CachedChunk(chunkId, 0, size, snapshot.version.toByte())
        }
        chunkMap[chunkId] = cachedChunk.copy(refCount = cachedChunk.refCount + 1)
    }

}
