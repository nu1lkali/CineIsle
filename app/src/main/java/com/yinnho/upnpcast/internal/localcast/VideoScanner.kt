package com.yinnho.upnpcast.internal.localcast

import android.content.Context
import android.util.Log
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.internal.FileUtils

/**
 * Local video file scanner using MediaStore
 */
internal class VideoScanner(private val context: Context) {
    
    companion object {
        private const val TAG = "VideoScanner"
        private const val VIDEO_MIME_TYPE_FILTER = "video/%"
        private const val UNKNOWN_VIDEO_TITLE = "Unknown Video"
        private const val MIN_VALID_FILE_SIZE = 0L
        private const val BATCH_SIZE = 50
        private const val MAX_VIDEO_COUNT = 1000
    }
    
    /**
     * Scan all local video files using MediaStore
     */
    fun scanLocalVideos(callback: (videos: List<DLNACast.LocalVideo>) -> Unit) {
        Log.d(TAG, "Starting to scan local video files")
        Thread {
            try {
                val videos = mutableListOf<DLNACast.LocalVideo>()
                
                val projection = arrayOf(
                    android.provider.MediaStore.Video.Media._ID,
                    android.provider.MediaStore.Video.Media.TITLE,
                    android.provider.MediaStore.Video.Media.DATA,
                    android.provider.MediaStore.Video.Media.DURATION,
                    android.provider.MediaStore.Video.Media.SIZE,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME
                )
                
                val selection = "${android.provider.MediaStore.Video.Media.MIME_TYPE} LIKE '$VIDEO_MIME_TYPE_FILTER'"
                
                val cursor = try {
                    context.contentResolver.query(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        null,
                        "${android.provider.MediaStore.Video.Media.DATE_MODIFIED} DESC"
                    )
                } catch (e: SecurityException) {
                    Log.e(TAG, "MediaStore query permission denied: ${e.message}")
                    callback(emptyList())
                    return@Thread
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "MediaStore query parameter error: ${e.message}")
                    callback(emptyList())
                    return@Thread
                }
                
                cursor?.use {
                    val totalRows = it.count
                    Log.d(TAG, "MediaStore scanned $totalRows video records")
                    
                    val idColumn = it.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                    val titleColumn = it.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.TITLE)
                    val dataColumn = it.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA)
                    val durationColumn = it.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DURATION)
                    val sizeColumn = it.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.SIZE)
                    val displayNameColumn = it.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                    
                    var processedCount = 0
                    var validCount = 0
                    var securityErrorCount = 0
                    var ioErrorCount = 0
                    
                    while (it.moveToNext()) {
                        try {
                            processedCount++
                            
                            if (validCount >= MAX_VIDEO_COUNT) {
                                Log.w(TAG, "Reached maximum video count limit ($MAX_VIDEO_COUNT), stopping scan")
                                break
                            }
                            
                            if (processedCount % BATCH_SIZE == 0) {
                                System.gc()
                                val runtime = Runtime.getRuntime()
                                val memoryUsagePercent = ((runtime.totalMemory() - runtime.freeMemory()) * 100) / runtime.maxMemory()
                                if (memoryUsagePercent > 80) {
                                    Thread.sleep(100)
                                }
                            }
                            
                            val id = it.getString(idColumn)
                            val title = it.getString(titleColumn) ?: it.getString(displayNameColumn) ?: UNKNOWN_VIDEO_TITLE
                            val path = it.getString(dataColumn)
                            val durationMs = it.getLong(durationColumn)
                            val sizeBytes = it.getLong(sizeColumn)
                            
                            if (!path.isNullOrEmpty()) {
                                try {
                                    val file = java.io.File(path)
                                    if (file.exists() && file.length() > MIN_VALID_FILE_SIZE && file.isFile && file.canRead()) {
                                        if (FileUtils.isVideoFile(file.name.lowercase())) {
                                            validCount++
                                            videos.add(DLNACast.LocalVideo(
                                                id = id,
                                                title = title,
                                                path = path,
                                                duration = FileUtils.formatDuration(durationMs),
                                                size = FileUtils.formatFileSize(sizeBytes),
                                                durationMs = durationMs
                                            ))
                                        }
                                    }
                                } catch (e: SecurityException) {
                                    securityErrorCount++
                                } catch (e: java.io.IOException) {
                                    ioErrorCount++
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error processing video record: ${e.message}")
                        }
                    }
                    
                    Log.i(TAG, "Scan completed: Total=$totalRows, Valid=$validCount")
                    if (securityErrorCount > 0 || ioErrorCount > 0) {
                        Log.w(TAG, "Errors: Security=$securityErrorCount, IO=$ioErrorCount")
                    }
                } ?: run {
                    Log.w(TAG, "MediaStore query returned null cursor")
                }
                
                Log.i(TAG, "Found ${videos.size} usable video files")
                callback(videos)
                
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Not enough memory: ${e.message}")
                callback(emptyList())
            } catch (e: SecurityException) {
                Log.e(TAG, "Insufficient permissions: ${e.message}")
                callback(emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan videos: ${e.message}")
                callback(emptyList())
            }
        }.start()
    }
} 