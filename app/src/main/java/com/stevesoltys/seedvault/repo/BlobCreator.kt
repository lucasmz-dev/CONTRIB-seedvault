/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import androidx.annotation.WorkerThread
import com.github.luben.zstd.ZstdOutputStream
import com.google.protobuf.ByteString
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import com.stevesoltys.seedvault.proto.SnapshotKt.blob
import com.stevesoltys.seedvault.repo.Padding.getPadTo
import okio.Buffer
import org.calyxos.seedvault.chunker.Chunk
import org.calyxos.seedvault.core.MemoryLogger
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.BackendSaver
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Creates and uploads new blobs to the current backend.
 */
internal class BlobCreator(
    private val crypto: Crypto,
    private val backendManager: BackendManager,
) {

    private val payloadBuffer = Buffer()
    private val buffer = Buffer()

    /**
     * Creates and returns a new [Blob] from the given [chunk] and uploads it to the backend.
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun createNewBlob(chunk: Chunk): Blob {
        // ensure buffers are cleared
        payloadBuffer.clear()
        buffer.clear()

        // compress payload and get size
        ZstdOutputStream(payloadBuffer.outputStream()).use { zstdOutputStream ->
            zstdOutputStream.write(chunk.data)
        }
        val payloadSize = payloadBuffer.size.toInt()
        val payloadSizeBytes = ByteBuffer.allocate(4).putInt(payloadSize).array()
        val paddingSize = getPadTo(payloadSize) - payloadSize

        // encrypt compressed payload and assemble entire blob
        val bufferStream = buffer.outputStream()
        bufferStream.write(VERSION.toInt())
        crypto.newEncryptingStream(bufferStream, crypto.getAdForVersion()).use { cryptoStream ->
            cryptoStream.write(payloadSizeBytes)
            payloadBuffer.writeTo(cryptoStream)
            // add padding
            // we could just write 0s, but because of defense in depth, we use random bytes
            cryptoStream.write(crypto.getRandomBytes(paddingSize))
        }
        MemoryLogger.log()
        payloadBuffer.clear()

        // compute hash and save blob
        val sha256ByteString = buffer.sha256()
        val handle = AppBackupFileType.Blob(crypto.repoId, sha256ByteString.hex())
        val saver = object : BackendSaver {
            override val size: Long get() = buffer.size
            override val sha256: String get() = sha256ByteString.hex()
            override fun save(outputStream: OutputStream): Long {
                return buffer.copyTo(outputStream).size
            }
        }
        val size = backendManager.backend.save(handle, saver)
        buffer.clear()
        return blob {
            id = ByteString.copyFrom(sha256ByteString.asByteBuffer())
            length = size.toInt()
            uncompressedLength = chunk.length
        }
    }
}
