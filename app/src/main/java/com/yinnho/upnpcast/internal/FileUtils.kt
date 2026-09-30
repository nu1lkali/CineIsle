package com.yinnho.upnpcast.internal

import java.util.Locale

/**
 * File utility class
 * Provides common utility methods for file operations
 * 
 * Extracted file handling logic from DLNACastImpl
 */
internal object FileUtils {
    
    /**
     * Format video duration
     * @param durationMs Duration in milliseconds
     * @return Formatted duration string (e.g., "1:23:45" or "12:34")
     */
    fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes % 60, seconds % 60)
            else -> String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60)
        }
    }
    
    /**
     * Format file size
     * @param sizeBytes File size in bytes
     * @return Formatted file size string (e.g., "1.2 GB", "123.4 MB", "567 KB")
     */
    fun formatFileSize(sizeBytes: Long): String {
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1 -> String.format(Locale.ROOT, "%.1f GB", gb)
            mb >= 1 -> String.format(Locale.ROOT, "%.1f MB", mb)
            else -> String.format(Locale.ROOT, "%.0f KB", kb)
        }
    }
    
    /**
     * Check if it's a supported video file format
     * @param fileName File name
     * @return true if it's a supported video format
     */
    fun isVideoFile(fileName: String): Boolean {
        val lowerName = fileName.lowercase()
        return lowerName.endsWith(".mp4") || 
               lowerName.endsWith(".mkv") || 
               lowerName.endsWith(".avi") || 
               lowerName.endsWith(".mov") || 
               lowerName.endsWith(".wmv") || 
               lowerName.endsWith(".flv") || 
               lowerName.endsWith(".webm") ||
               lowerName.endsWith(".m4v") ||
               lowerName.endsWith(".3gp")
    }

}