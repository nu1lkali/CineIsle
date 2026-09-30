package com.yinnho.upnpcast.internal

/**
 * Minimalist UPnP exception system
 */
sealed class UPnPException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkError(message: String, cause: Throwable? = null) : UPnPException(message, cause)
    class DeviceError(message: String, cause: Throwable? = null) : UPnPException(message, cause)
    class FileError(message: String, cause: Throwable? = null) : UPnPException(message, cause)
    class MediaError(message: String, cause: Throwable? = null) : UPnPException(message, cause)
    class UnknownError(message: String, cause: Throwable? = null) : UPnPException(message, cause)
}

/**
 * Automatic exception conversion
 */
fun Throwable.toUPnPException(): UPnPException = when (this) {
    is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.UnknownHostException -> 
        UPnPException.NetworkError(message ?: "Network error", this)
    is java.io.FileNotFoundException -> UPnPException.FileError(message ?: "File not found", this)
    is SecurityException -> UPnPException.FileError(message ?: "Access denied", this)
    is UPnPException -> this
    else -> UPnPException.UnknownError(message ?: "Unknown error", this)
} 