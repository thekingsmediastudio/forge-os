package com.forge.os.service

import android.content.Context
import com.forge.os.data.sandbox.SandboxManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.io.NoSuchFileException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages file synchronization with chunked uploads from desktop clients.
 * Handles:
 * - Receiving file chunks
 * - Reassembling complete files
 * - Checksum verification
 * - Cleanup of incomplete uploads
 */
@Singleton
class SyncService @Inject constructor(
    private val context: Context,
    private val sandboxManager: SandboxManager,
    private val eventBroadcaster: EventBroadcaster
) {
    
    // Track ongoing uploads: path -> UploadState
    private val uploads = mutableMapOf<String, UploadState>()
    private val uploadsMutex = Mutex()
    
    // Temporary directory for chunked uploads
    private val tempDir: File = File(context.filesDir, "sync_temp").apply { mkdirs() }
    
    /**
     * Processes a file chunk upload.
     * 
     * @param path Workspace-relative file path
     * @param chunk Chunk index (0-based)
     * @param totalChunks Total number of chunks
     * @param checksum Expected SHA-256 hash of the complete file
     * @param data Binary chunk data
     * @return Upload status including received chunks and completion status
     */
    suspend fun processChunk(
        path: String,
        chunk: Int,
        totalChunks: Int,
        checksum: String,
        data: ByteArray
    ): UploadResult {
        // Validate inputs
        require(chunk >= 0) { "Chunk index must be non-negative" }
        require(chunk < totalChunks) { "Chunk index must be less than totalChunks" }
        require(totalChunks > 0) { "Total chunks must be positive" }
        require(path.isNotBlank()) { "Path must not be blank" }
        require(checksum.isNotBlank()) { "Checksum must not be blank" }
        
        return uploadsMutex.withLock {
            val state = uploads.getOrPut(path) {
                UploadState(
                    path = path,
                    totalChunks = totalChunks,
                    checksum = checksum,
                    chunks = mutableMapOf()
                )
            }
            
            // Validate consistency
            if (state.totalChunks != totalChunks) {
                throw IllegalStateException("Total chunks mismatch: expected ${state.totalChunks}, got $totalChunks")
            }
            if (state.checksum != checksum) {
                throw IllegalStateException("Checksum mismatch: expected ${state.checksum}, got $checksum")
            }
            
            // Store chunk to temp file
            val chunkFile = File(tempDir, "${path.hashCode()}_$chunk")
            chunkFile.parentFile?.mkdirs()
            chunkFile.writeBytes(data)
            state.chunks[chunk] = chunkFile
            
            Timber.d("SyncService: Received chunk $chunk/$totalChunks for $path (${data.size} bytes)")
            
            // Check if upload is complete
            val receivedChunks = state.chunks.keys.sorted()
            val complete = receivedChunks.size == totalChunks
            
            if (complete) {
                // Reassemble and verify
                try {
                    reassembleAndVerify(state)
                    uploads.remove(path)
                    Timber.i("SyncService: Upload complete for $path")
                } catch (e: Exception) {
                    // Cleanup on failure
                    cleanupUpload(state)
                    uploads.remove(path)
                    throw e
                }
            }
            
            UploadResult(
                uploaded = true,
                receivedChunks = receivedChunks,
                complete = complete
            )
        }
    }
    
    /**
     * Reassembles chunks into final file and verifies checksum.
     */
    private suspend fun reassembleAndVerify(state: UploadState) {
        // Create a temp file for reassembly
        val tempFile = File(tempDir, "${state.path.hashCode()}_complete")
        
        try {
            // Combine all chunks in order
            tempFile.outputStream().use { output ->
                for (i in 0 until state.totalChunks) {
                    val chunkFile = state.chunks[i] 
                        ?: throw IllegalStateException("Missing chunk $i")
                    chunkFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            
            // Verify checksum
            val actualChecksum = calculateSHA256(tempFile)
            if (actualChecksum != state.checksum) {
                throw SecurityException(
                    "Checksum verification failed: expected ${state.checksum}, got $actualChecksum"
                )
            }
            
            // Write to workspace using SandboxManager.
            // gzip-compressed uploads (9.6) are decompressed before storing.
            val content = if (state.compressed) {
                tempFile.inputStream().use { raw ->
                    java.util.zip.GZIPInputStream(raw).use { gz -> gz.readBytes() }
                }
            } else {
                tempFile.readBytes()
            }
            val workspacePath = sandboxManager.getWorkspacePath()
            val targetFile = File(workspacePath, state.path)
            
            // Ensure parent directories exist
            targetFile.parentFile?.mkdirs()
            
            // Write the file
            targetFile.writeBytes(content)
            
            Timber.i("SyncService: File written to ${targetFile.absolutePath}, size=${content.size} bytes")

            // Notify the desktop so it can mirror the change (Task 9.3)
            eventBroadcaster.emitFileModified(state.path, state.checksum, content.size.toLong())
            
        } finally {
            // Cleanup temp files
            cleanupUpload(state)
            tempFile.delete()
        }
    }
    
    /**
     * Calculates SHA-256 hash of a file.
     */
    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Cleans up temporary chunk files for an upload.
     */
    private fun cleanupUpload(state: UploadState) {
        state.chunks.values.forEach { chunkFile ->
            runCatching { chunkFile.delete() }
        }
    }
    
    /**
     * Gets the status of an ongoing upload.
     */
    suspend fun getUploadStatus(path: String): UploadState? {
        return uploadsMutex.withLock {
            uploads[path]
        }
    }
    
    /**
     * Cancels an ongoing upload and cleans up temp files.
     */
    suspend fun cancelUpload(path: String): Boolean {
        return uploadsMutex.withLock {
            val state = uploads.remove(path)
            if (state != null) {
                cleanupUpload(state)
                Timber.i("SyncService: Cancelled upload for $path")
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Downloads a file from the workspace.
     * 
     * @param path Workspace-relative file path
     * @param rangeStart Optional byte offset for range requests (inclusive)
     * @param rangeEnd Optional byte offset for range requests (inclusive)
     * @return Download result containing file data and metadata
     */
    suspend fun downloadFile(
        path: String,
        rangeStart: Long? = null,
        rangeEnd: Long? = null
    ): DownloadResult {
        // Validate inputs
        require(path.isNotBlank()) { "Path must not be blank" }
        
        // Get workspace path and resolve file
        val workspacePath = sandboxManager.getWorkspacePath()
        val file = File(workspacePath, path)
        
        // Check if file exists
        if (!file.exists()) {
            throw NoSuchFileException(file, reason = "File not found: $path")
        }
        
        if (!file.isFile) {
            throw IllegalArgumentException("Path is not a file: $path")
        }
        
        val fileSize = file.length()
        
        // Handle range request
        val actualStart = rangeStart?.coerceAtLeast(0) ?: 0
        val actualEnd = rangeEnd?.coerceAtMost(fileSize - 1) ?: (fileSize - 1)
        
        // Validate range
        if (actualStart > actualEnd || actualStart >= fileSize) {
            throw RangeNotSatisfiableException(
                "Invalid range: $actualStart-$actualEnd, file size: $fileSize"
            )
        }
        
        // Read the requested byte range
        val data = file.inputStream().use { input ->
            // Skip to start position
            var skipped = 0L
            while (skipped < actualStart) {
                val n = input.skip(actualStart - skipped)
                if (n <= 0) break
                skipped += n
            }
            
            // Read the range
            val length = (actualEnd - actualStart + 1).toInt()
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n <= 0) break
                read += n
            }
            buffer.sliceArray(0 until read)
        }
        
        Timber.d("SyncService: Downloaded $path range $actualStart-$actualEnd (${data.size} bytes)")
        
        return DownloadResult(
            data = data,
            totalSize = fileSize,
            rangeStart = actualStart,
            rangeEnd = actualStart + data.size - 1,
            isPartial = rangeStart != null || rangeEnd != null
        )
    }
    
    /**
     * Cleans up stale uploads that may have been abandoned.
     * Should be called periodically or on app start.
     */
    /**
     * Stats a workspace file for conflict detection (Task 9.5).
     */
    suspend fun statFile(path: String): com.forge.os.data.api.FileStatResponse {
        val workspacePath = sandboxManager.getWorkspacePath()
        val file = File(workspacePath, path)
        if (!file.exists() || !file.isFile) {
            return com.forge.os.data.api.FileStatResponse(exists = false)
        }
        return com.forge.os.data.api.FileStatResponse(
            exists = true,
            size = file.length(),
            lastModified = file.lastModified(),
            checksum = calculateSHA256(file)
        )
    }

    fun cleanupStaleUploads() {
        val tempFiles = tempDir.listFiles() ?: return
        tempFiles.forEach { file ->
            runCatching { file.delete() }
        }
        Timber.i("SyncService: Cleaned up ${tempFiles.size} stale temp files")
    }
}

/**
 * Custom exception for range requests that cannot be satisfied.
 */
class RangeNotSatisfiableException(message: String) : Exception(message)

/**
 * State tracking for an in-progress upload.
 */
data class UploadState(
    val path: String,
    val totalChunks: Int,
    val checksum: String,
    val compressed: Boolean = false,
    val chunks: MutableMap<Int, File> = mutableMapOf()
)

/**
 * Result of a chunk upload operation.
 */
data class UploadResult(
    val uploaded: Boolean,
    val receivedChunks: List<Int>,
    val complete: Boolean
)

/**
 * Result of a file download operation.
 */
data class DownloadResult(
    val data: ByteArray,
    val totalSize: Long,
    val rangeStart: Long,
    val rangeEnd: Long,
    val isPartial: Boolean
)
