package app.marlboroadvance.mpvex.preferences

import android.content.Context
import android.net.Uri
import android.util.Xml
import app.marlboroadvance.mpvex.database.MpvExDatabase
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.utils.security.withEncryptedPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class SettingsManager(
  private val context: Context,
  private val preferenceStore: PreferenceStore,
  private val database: MpvExDatabase,
) {
  companion object {
    private const val TAG_ROOT = "mpvExSettings"
    private const val TAG_PREFERENCES = "preferences"
    private const val TAG_PREFERENCE = "preference"
    private const val TAG_DATABASE = "database"
    private const val TAG_NETWORK_CONNECTIONS = "networkConnections"
    private const val TAG_NETWORK_CONNECTION = "networkConnection"
    
    private const val ATTR_KEY = "key"
    private const val ATTR_TYPE = "type"
    private const val ATTR_VALUE = "value"
    private const val ATTR_EXPORT_DATE = "exportDate"
    private const val ATTR_VERSION = "version"

    private const val TYPE_STRING = "string"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_STRING_SET = "stringSet"
    private const val STRING_SET_SEPARATOR = "|||"
  }


  suspend fun exportSettings(outputUri: Uri): Result<ExportStats> =
    withContext(Dispatchers.IO) {
      try {
        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
          val stats = writeSettingsToXml(outputStream)
          Result.success(stats)
        } ?: Result.failure(Exception("Failed to open output stream"))
      } catch (e: Exception) {
        Result.failure(e)
      }
    }


  suspend fun importSettings(inputUri: Uri): Result<ImportStats> =
    withContext(Dispatchers.IO) {
      try {
        context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
          val stats = readSettingsFromXml(inputStream)
          Result.success(stats)
        } ?: Result.failure(Exception("Failed to open input stream"))
      } catch (e: Exception) {
        Result.failure(e)
      }
    }


  private suspend fun writeSettingsToXml(outputStream: OutputStream): ExportStats {
    val serializer: XmlSerializer = Xml.newSerializer()
    serializer.setOutput(outputStream, "UTF-8")
    serializer.startDocument("UTF-8", true)
    serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)

    serializer.startTag(null, TAG_ROOT)
    serializer.attribute(
      null,
      ATTR_EXPORT_DATE,
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
    )

    var exportedCount = 0
    val exportedKeys = mutableListOf<String>()

    serializer.startTag(null, TAG_PREFERENCES)
    val allPreferences = preferenceStore.getAll()
    for ((key, value) in allPreferences) {
      if (value != null) {
        writePreference(serializer, key, value)
        exportedCount++
        exportedKeys.add("pref:$key")
      }
    }
    serializer.endTag(null, TAG_PREFERENCES)

    serializer.startTag(null, TAG_DATABASE)

    val networkConnections = database.networkConnectionDao().getAllConnectionsList()
    serializer.startTag(null, TAG_NETWORK_CONNECTIONS)
    val passwordDropped = mutableListOf<String>()
    networkConnections.forEach { connection ->
      writeNetworkConnection(serializer, connection)
      // 备份文件不落密码：它是明文 XML，可能被扔进网盘/聊天窗口，
      // 一旦带密码就是把所有服务器的口令一起送出去。导入后重新输一次即可。
      if (connection.password.isNotEmpty()) passwordDropped.add(connection.name)
      exportedCount++
      exportedKeys.add("network:${connection.name}")
    }
    serializer.endTag(null, TAG_NETWORK_CONNECTIONS)

    serializer.endTag(null, TAG_DATABASE)

    serializer.endTag(null, TAG_ROOT)
    serializer.endDocument()
    serializer.flush()

    return ExportStats(
      totalExported = exportedCount,
      exportedKeys = exportedKeys,
      passwordDroppedFor = passwordDropped,
    )
  }


  private fun writePreference(
    serializer: XmlSerializer,
    key: String,
    value: Any,
  ) {
    serializer.startTag(null, TAG_PREFERENCE)
    serializer.attribute(null, ATTR_KEY, key)

    when (value) {
      is String -> {
        serializer.attribute(null, ATTR_TYPE, TYPE_STRING)
        serializer.attribute(null, ATTR_VALUE, value)
      }
      is Int -> {
        serializer.attribute(null, ATTR_TYPE, TYPE_INT)
        serializer.attribute(null, ATTR_VALUE, value.toString())
      }
      is Long -> {
        serializer.attribute(null, ATTR_TYPE, TYPE_LONG)
        serializer.attribute(null, ATTR_VALUE, value.toString())
      }
      is Float -> {
        serializer.attribute(null, ATTR_TYPE, TYPE_FLOAT)
        serializer.attribute(null, ATTR_VALUE, value.toString())
      }
      is Boolean -> {
        serializer.attribute(null, ATTR_TYPE, TYPE_BOOLEAN)
        serializer.attribute(null, ATTR_VALUE, value.toString())
      }
      is Set<*> -> {
        @Suppress("UNCHECKED_CAST")
        val stringSet = value as? Set<String>
        if (stringSet != null) {
          serializer.attribute(null, ATTR_TYPE, TYPE_STRING_SET)
          // Encode string set by joining with separator
          serializer.attribute(null, ATTR_VALUE, stringSet.joinToString(STRING_SET_SEPARATOR))
        }
      }
    }

    serializer.endTag(null, TAG_PREFERENCE)
  }


  private fun writeNetworkConnection(
    serializer: XmlSerializer,
    connection: NetworkConnection,
  ) {
    serializer.startTag(null, TAG_NETWORK_CONNECTION)
    serializer.attribute(null, "id", connection.id.toString())
    serializer.attribute(null, "name", connection.name)
    serializer.attribute(null, "protocol", connection.protocol.name)
    serializer.attribute(null, "host", connection.host)
    serializer.attribute(null, "port", connection.port.toString())
    serializer.attribute(null, "username", connection.username)
    // 密码**不写进备份**，理由见 writeSettingsToXml 里的注释。
    // 保留 passwordOmitted 标记，导入时能区分「本来就没密码」和「备份里被剥掉了」，
    // 便于给出「请重新输入密码」的提示。
    if (connection.password.isNotEmpty()) {
      serializer.attribute(null, "passwordOmitted", "true")
    }
    serializer.attribute(null, "path", connection.path)
    serializer.attribute(null, "isAnonymous", connection.isAnonymous.toString())
    serializer.attribute(null, "lastConnected", connection.lastConnected.toString())
    serializer.attribute(null, "autoConnect", connection.autoConnect.toString())
    serializer.endTag(null, TAG_NETWORK_CONNECTION)
  }

  private suspend fun readSettingsFromXml(inputStream: InputStream): ImportStats {
    val parser: XmlPullParser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(inputStream, "UTF-8")

    val stats = ImportStats()
    var eventType = parser.eventType

    val networkConnections = mutableListOf<NetworkConnection>()

    while (eventType != XmlPullParser.END_DOCUMENT) {
      when (eventType) {
        XmlPullParser.START_TAG -> {
          when (parser.name) {
            TAG_ROOT -> {
              val version = parser.getAttributeValue(null, ATTR_VERSION)
              stats.version = version ?: "unknown"
            }
            TAG_PREFERENCE -> {
              try {
                readPreference(parser)
                stats.imported++
              } catch (e: Exception) {
                stats.failed++
                stats.errors.add("Failed to import preference: ${e.message}")
              }
            }
            TAG_NETWORK_CONNECTION -> {
              try {
                networkConnections.add(readNetworkConnection(parser))
                if (parser.getAttributeValue(null, "passwordOmitted") == "true") {
                  val name = parser.getAttributeValue(null, "name").orEmpty()
                  stats.notices.add("连接「$name」的密码不在备份中，导入后请重新输入")
                }
                stats.imported++
              } catch (e: Exception) {
                stats.failed++
                stats.errors.add("Failed to import network connection: ${e.message}")
              }
            }
          }
        }
      }
      eventType = parser.next()
    }

    // Insert all database data
    try {
      if (networkConnections.isNotEmpty()) {
        // 老版本备份里可能带明文密码，落库前统一加密 —— 保证 DB 里不出现明文。
        database.networkConnectionDao().insertAll(
          networkConnections.map { it.withEncryptedPassword() },
        )
      }
    } catch (e: Exception) {
      stats.failed++
      stats.errors.add("Failed to insert database data: ${e.message}")
    }

    return stats
  }


  private fun readPreference(parser: XmlPullParser) {
    val key = parser.getAttributeValue(null, ATTR_KEY) ?: return
    val type = parser.getAttributeValue(null, ATTR_TYPE) ?: return
    val valueStr = parser.getAttributeValue(null, ATTR_VALUE) ?: return

    // Get the SharedPreferences editor from the Android context
    val sharedPrefs =
      androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    val editor = sharedPrefs.edit()

    when (type) {
      TYPE_STRING -> editor.putString(key, valueStr)
      TYPE_INT -> editor.putInt(key, valueStr.toIntOrNull() ?: 0)
      TYPE_LONG -> editor.putLong(key, valueStr.toLongOrNull() ?: 0L)
      TYPE_FLOAT -> editor.putFloat(key, valueStr.toFloatOrNull() ?: 0f)
      TYPE_BOOLEAN -> editor.putBoolean(key, valueStr.toBoolean())
      TYPE_STRING_SET -> {
        val stringSet =
          if (valueStr.isEmpty()) {
            emptySet()
          } else {
            valueStr.split(STRING_SET_SEPARATOR).toSet()
          }
        editor.putStringSet(key, stringSet)
      }
    }

    editor.apply()
  }


  private fun readNetworkConnection(parser: XmlPullParser): NetworkConnection {
    return NetworkConnection(
      id = 0, // Will be auto-generated
      name = parser.getAttributeValue(null, "name") ?: "",
      protocol =
        NetworkProtocol.valueOf(
          parser.getAttributeValue(null, "protocol") ?: "SMB",
        ),
      host = parser.getAttributeValue(null, "host") ?: "",
      port = parser.getAttributeValue(null, "port")?.toIntOrNull() ?: 445,
      username = parser.getAttributeValue(null, "username") ?: "",
      password = parser.getAttributeValue(null, "password") ?: "",
      path = parser.getAttributeValue(null, "path") ?: "/",
      isAnonymous = parser.getAttributeValue(null, "isAnonymous")?.toBoolean() ?: false,
      lastConnected = parser.getAttributeValue(null, "lastConnected")?.toLongOrNull() ?: 0L,
      autoConnect = parser.getAttributeValue(null, "autoConnect")?.toBoolean() ?: false,
    )
  }

  fun getDefaultExportFilename(): String {
    val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    return "mpvEx_settings_${dateFormat.format(Date())}.xml"
  }

  data class ImportStats(
    var imported: Int = 0,
    var failed: Int = 0,
    var version: String = "unknown",
    val errors: MutableList<String> = mutableListOf(),
    /** 非致命提示，例如「这条连接的密码不在备份里，需要重输」 */
    val notices: MutableList<String> = mutableListOf(),
  )

  data class ExportStats(
    val totalExported: Int,
    val exportedKeys: List<String>,
    /** 出于安全考虑未把密码写进备份的连接名 */
    val passwordDroppedFor: List<String> = emptyList(),
  )
}
