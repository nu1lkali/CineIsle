package com.yinnho.upnpcast.internal.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared HTTP execution for UPnP traffic (device description retrieval and
 * SOAP control requests), so timeouts, headers and connection handling stay
 * consistent across the library
 */
internal object UpnpHttp {

    private const val USER_AGENT = "UPnPCast/1.0"

    /**
     * [CineIsle-DLNA] 最近一次 SOAP 失败时设备返回的原始错误。
     *
     * UPnP 规定控制请求失败时返回 HTTP 500，body 里带 <errorCode> 与
     * <errorDescription>（例如 714 Illegal mime-type、701 Play speed not
     * supported）。上游实现把错误响应整个丢掉只返回 null，上层只能笼统提示
     * 「设备拒绝了投屏请求」，无法判断到底是格式不支持、URL 不可达还是设备忙。
     * 这里把它留存下来，经 CoreManager.lastError() / DLNACast.getLastError() 暴露给 UI。
     */
    @Volatile
    var lastSoapError: String? = null
        private set

    fun resetLastError() {
        lastSoapError = null
    }

    /** [CineIsle-DLNA] 供调用方记录「还没走到 SOAP 就已经失败」的原因。 */
    fun setSyntheticError(message: String) {
        lastSoapError = message
    }

    /**
     * GET a resource body; returns null on failure. Retries transient
     * failures (non-200 or IO error) up to [maxRetries] times with a linear
     * backoff of [retryDelayMs] * attempt.
     */
    suspend fun get(
        url: String,
        connectTimeoutMs: Int = 5000,
        readTimeoutMs: Int = 10000,
        maxRetries: Int = 1,
        retryDelayMs: Long = 1000L
    ): String? = withContext(Dispatchers.IO) {
        repeat(maxRetries) { attempt ->
            val attemptNumber = attempt + 1
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                        return@withContext reader.readText()
                    }
                }
            } catch (e: Exception) {
                // Fall through to retry
            } finally {
                connection?.disconnect()
            }
            if (attemptNumber < maxRetries) {
                delay(retryDelayMs * attemptNumber)
            }
        }
        null
    }

    /**
     * POST a SOAP envelope with the given action header; returns the
     * response body on HTTP 200, null otherwise.
     */
    suspend fun postSoap(
        url: String,
        soapAction: String,
        body: String,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 5000
    ): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"$soapAction\"")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs

            connection.outputStream.use { outputStream ->
                OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                    writer.write(soapEnvelope(body))
                    writer.flush()
                }
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                lastSoapError = null
                BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                    reader.readText()
                }
            } else {
                // [CineIsle-DLNA] 把设备返回的错误正文解析出来，别再静默吞掉
                lastSoapError = describeFailure(connection, soapAction, null)
                null
            }
        } catch (e: Exception) {
            lastSoapError = describeFailure(connection, soapAction, e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** [CineIsle-DLNA] 把 HTTP 状态码 / UPnP 错误码 / 异常拼成一句可读信息。 */
    private fun describeFailure(
        connection: HttpURLConnection?,
        soapAction: String,
        cause: Exception?
    ): String {
        val body = runCatching {
            connection?.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8)
        }.getOrNull()

        val code = body?.let {
            Regex("<errorCode>(.*?)</errorCode>", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)?.trim()
        }
        val desc = body?.let {
            Regex("<errorDescription>(.*?)</errorDescription>", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)?.trim()
        }

        return buildString {
            append(soapAction).append(" 失败")
            runCatching { connection?.responseCode }.getOrNull()?.takeIf { it > 0 }
                ?.let { append(" (HTTP ").append(it).append(")") }
            when {
                !code.isNullOrBlank() || !desc.isNullOrBlank() -> {
                    append(": ")
                    if (!code.isNullOrBlank()) append("UPnP ").append(code)
                    if (!desc.isNullOrBlank()) {
                        if (!code.isNullOrBlank()) append(" ")
                        append(desc)
                    }
                }
                !body.isNullOrBlank() -> append(": ").append(body.trim().take(200))
                cause != null -> append(": ").append(cause.javaClass.simpleName)
                    .append(" ").append(cause.message)
            }
        }
    }

    fun soapEnvelope(body: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
                $body
            </s:Body>
        </s:Envelope>
    """.trimIndent()
}
