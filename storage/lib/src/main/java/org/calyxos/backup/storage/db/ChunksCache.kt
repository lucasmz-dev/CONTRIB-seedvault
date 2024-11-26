/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.db

import androidx.annotation.VisibleForTesting
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.PrimaryKey
import androidx.room.Query
import org.calyxos.backup.storage.backup.Backup

@Entity
internal data class CachedChunk(
    @PrimaryKey val id: String,
    /**
     * How many snapshots are referencing this chunk.
     * Note that this is *not* about how many files across various snapshots are referencing it.
     */
    @ColumnInfo(name = "ref_count") val refCount: Long,
    val size: Long,
    val version: Byte = Backup.VERSION,
    @ColumnInfo(defaultValue = "0")
    val corrupted: Boolean = false,
)

@Dao
internal interface ChunksCache {
    @Insert(onConflict = REPLACE)
    fun insert(chunk: CachedChunk)

    @Insert(onConflict = REPLACE)
    fun insert(chunks: Collection<CachedChunk>)

    /**
     * Returns the [CachedChunk] with the given [id], if it exists in the database
     * and is not marked as [CachedChunk.corrupted].
     */
    @Query("SELECT * FROM CachedChunk WHERE id == (:id) AND corrupted = 0")
    fun get(id: String): CachedChunk?

    /**
     * Returns the [CachedChunk] with the given [id], if it exists in the database
     * no mater if it is marked as [CachedChunk.corrupted] or not.
     */
    @Query("SELECT * FROM CachedChunk WHERE id == (:id)")
    fun getEvenIfCorrupted(id: String): CachedChunk?

    @VisibleForTesting
    @Query("SELECT COUNT(id) FROM CachedChunk WHERE id IN (:ids)")
    fun getNumberOfCachedChunks(ids: Collection<String>): Int

    @Query("SELECT SUM(size) FROM CachedChunk WHERE ref_count > 0")
    fun getSizeOfCachedChunks(): Long

    @Query("SELECT * FROM CachedChunk WHERE ref_count <= 0")
    fun getUnreferencedChunks(): List<CachedChunk>

    @Query("UPDATE CachedChunk SET ref_count = ref_count + 1 WHERE id IN (:ids)")
    fun incrementRefCount(ids: Collection<String>)

    @Query("UPDATE CachedChunk SET ref_count = ref_count - 1 WHERE id IN (:ids)")
    fun decrementRefCount(ids: Collection<String>)

    @Query("UPDATE CachedChunk SET corrupted = 1 WHERE id == :id")
    fun markCorrupted(id: String)

    @Delete
    fun deleteChunks(chunks: List<CachedChunk>)

    @Query("DELETE FROM CachedChunk")
    fun clear()

    fun areAllAvailableChunksCached(db: Db, availableChunks: Collection<String>): Boolean {
        return db.runInTransaction<Boolean> {
            availableChunks.chunked(DB_MAX_OP).forEach { availableChunkIds ->
                val num = getNumberOfCachedChunks(availableChunkIds)
                if (availableChunkIds.size != num) return@runInTransaction false
            }
            return@runInTransaction true
        }
    }

    fun clearAndRepopulate(db: Db, chunks: Collection<CachedChunk>) = db.runInTransaction {
        clear()
        chunks.chunked(DB_MAX_OP).forEach {
            insert(it)
        }
    }

}
