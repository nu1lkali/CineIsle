package app.marlboroadvance.mpvex.domain.smartmerge

import kotlinx.serialization.Serializable

/**
 * Smart Merge 的业务数据模型。
 *
 * 这里刻意与 Emby 的 `EmbyItem` 分开：扫描阶段把服务端对象投影成 App 自己的模型，
 * 后续候选、快照、历史、UI 都只依赖这一层。服务端字段变化不会直接改到 UI。
 */
@Serializable
data class SmartMergeItem(
  val id: String,
  val name: String,
  val originalTitle: String? = null,
  val type: String? = null,
  val path: String? = null,
  val providerIds: Map<String, String> = emptyMap(),
  /** 元数据来源的启发式判断：MetaTube / JavBoss / JavBus / 未知 */
  val source: String = SOURCE_UNKNOWN,
  /** 标准化后的番号（如 ABC-123，可能带后缀）；为空表示没认出来 */
  val code: String? = null,
  /** 去掉后缀的可比较主体（ABC123），候选分组用 */
  val codeBody: String? = null,
  val codeConfidence: Double = 0.0,
  val version: SmartMergeVersionProfile = SmartMergeVersionProfile(),
  val productionYear: Int? = null,
  val premiereDate: String? = null,
  val dateCreated: String? = null,
  val runtimeTicks: Long? = null,
  val overview: String? = null,
  val genres: List<String> = emptyList(),
  val studios: List<String> = emptyList(),
  val people: List<String> = emptyList(),
  val mediaSources: List<SmartMergeMediaSource> = emptyList(),
  val imageTag: String? = null,
  /** 已经是多版本条目：不要再推荐合并 */
  val alreadyMultiVersion: Boolean = false,
) {
  /** 展示用标题：优先 Name，其次 OriginalTitle，最后路径文件名 */
  val displayTitle: String
    get() = name.ifBlank {
      originalTitle?.takeIf { it.isNotBlank() }
        ?: path?.substringAfterLast('/')?.substringAfterLast('\\')
        ?: id
    }
}

@Serializable
data class SmartMergeMediaSource(
  val id: String? = null,
  val name: String? = null,
  val path: String? = null,
  val container: String? = null,
  val size: Long? = null,
  val bitrate: Long? = null,
  val runtimeTicks: Long? = null,
  val width: Int? = null,
  val height: Int? = null,
  val videoCodec: String? = null,
  val audioCodecs: List<String> = emptyList(),
  val subtitleLanguages: List<String> = emptyList(),
)

@Serializable
data class SmartMergeVersionProfile(
  /** 面向用户的短标签，如 `LTX · 1080P · 中字` */
  val label: String = "未知版本",
  /** NORMAL / LTX / SPECIAL / REPAIRED / UNKNOWN */
  val type: String = TYPE_UNKNOWN,
  /** NONE / CHINESE / EMBEDDED / EXTERNAL / MULTI / UNKNOWN */
  val subtitles: String = SUBTITLES_UNKNOWN,
  /** 4K / 1080P / 720P / SD / UNKNOWN */
  val quality: String = QUALITY_UNKNOWN,
  /** HEVC / H264 / AV1 / UNKNOWN */
  val codec: String = CODEC_UNKNOWN,
  /** REMUX / BLURAY / WEB-DL / HDTV / UNKNOWN */
  val source: String = SOURCE_UNKNOWN,
  val flags: List<String> = emptyList(),
)

@Serializable
data class SmartMergeCandidate(
  val id: String,
  val serverId: Long,
  val code: String,
  val title: String,
  val itemIds: List<String>,
  /** 候选详情展示用；与 itemIds 一一对应 */
  val items: List<SmartMergeItem> = emptyList(),
  val confidence: Int,
  val conflicts: List<String> = emptyList(),
  /** PENDING / HIGH / CONFLICT / MERGED / IGNORED / FAILED */
  val status: String = STATUS_PENDING,
  val sourceSummary: String = "",
  val versionSummary: String = "",
  val suggestedPrimaryId: String? = null,
  val createdAt: Long = 0L,
  val updatedAt: Long = 0L,
) {
  val isHighConfidence: Boolean get() = confidence >= 90
  val hasConflict: Boolean get() = conflicts.isNotEmpty()
}

@Serializable
data class SmartMergeItemSnapshot(
  val itemId: String,
  val name: String,
  val path: String? = null,
  val providerIds: Map<String, String> = emptyMap(),
  val mediaSourcePaths: List<String> = emptyList(),
  val userDataJson: String? = null,
  /** 原始详情 JSON 的落盘文件名；完整原文不进 Room/状态 JSON */
  val rawFile: String? = null,
)

@Serializable
data class SmartMergeOperation(
  val id: String,
  val serverId: Long,
  val candidateId: String? = null,
  val candidateCode: String? = null,
  val candidateTitle: String? = null,
  val originalItemIds: List<String>,
  val resultItemId: String? = null,
  /** 合并后 Emby 实际返回的 MediaSources 展示名（文件/版本名） */
  val resultVersionNames: List<String> = emptyList(),
  val snapshots: List<SmartMergeItemSnapshot> = emptyList(),
  /** RUNNING / DONE / VERIFY_FAILED / FAILED / SPLIT / SPLIT_FAILED */
  val status: String = OP_RUNNING,
  val serverVersion: String? = null,
  val createdAt: Long = 0L,
  val completedAt: Long? = null,
  val error: String? = null,
  /** UNTESTED / SUPPORTED / UNSUPPORTED：拆分能力必须先真机验证 */
  val splitSupport: String = SPLIT_UNTESTED,
)

@Serializable
data class SmartMergeIgnoreRule(
  val id: String,
  val serverId: Long,
  /** code / candidate / item / pair */
  val type: String,
  val value: String,
  val reason: String? = null,
  val createdAt: Long = 0L,
)

/**
 * 智能匹配规则（用户勾选）。
 *
 * 番号是基础身份，始终参与；其余维度可以关掉，用于「只想按 ProviderIds / 只想按番号」
 * 这类不同严格度的场景。`allowProviderOnlyGroups` 允许没有番号、但 ProviderIds 相同的
 * 条目单独成组（会给出较低置信度，仍需人工确认）。
 */
@Serializable
data class SmartMergeRules(
  val useProviderIds: Boolean = true,
  val useTitle: Boolean = true,
  val useActors: Boolean = true,
  val useDuration: Boolean = true,
  val allowProviderOnlyGroups: Boolean = false,
  /** 低于这个分数的候选不展示；40 / 70 / 90 由 UI 提供 */
  val minScore: Int = 40,
)

@Serializable
data class SmartMergeState(
  val serverId: Long = 0L,
  val candidates: List<SmartMergeCandidate> = emptyList(),
  val operations: List<SmartMergeOperation> = emptyList(),
  val ignoreRules: List<SmartMergeIgnoreRule> = emptyList(),
  val rules: SmartMergeRules = SmartMergeRules(),
  val lastScanAt: Long = 0L,
  val scannedCount: Int = 0,
  val lastScanLibraryIds: List<String> = emptyList(),
)

/** 扫描进度 */
data class SmartMergeScanProgress(
  val scanned: Int = 0,
  val total: Int = 0,
  val libraryName: String = "",
  val candidateCount: Int = 0,
)

// ── 常量（用字符串而不是 enum，避免序列化/数据库迁移时改名困难）──
const val SOURCE_UNKNOWN = "未知来源"
const val SOURCE_METATUBE = "MetaTube"
const val SOURCE_JAVBOSS = "JavBoss"
const val SOURCE_JAVBUS = "JavBus"

const val TYPE_NORMAL = "NORMAL"
const val TYPE_LTX = "LTX"
const val TYPE_SPECIAL = "SPECIAL"
const val TYPE_REPAIRED = "REPAIRED"
const val TYPE_UNKNOWN = "UNKNOWN"

const val SUBTITLES_NONE = "NONE"
const val SUBTITLES_CHINESE = "CHINESE"
const val SUBTITLES_EMBEDDED = "EMBEDDED"
const val SUBTITLES_EXTERNAL = "EXTERNAL"
const val SUBTITLES_MULTI = "MULTI"
const val SUBTITLES_UNKNOWN = "UNKNOWN"

const val QUALITY_4K = "4K"
const val QUALITY_1080P = "1080P"
const val QUALITY_720P = "720P"
const val QUALITY_SD = "SD"
const val QUALITY_UNKNOWN = "UNKNOWN"

const val CODEC_HEVC = "HEVC"
const val CODEC_H264 = "H264"
const val CODEC_AV1 = "AV1"
const val CODEC_UNKNOWN = "UNKNOWN"

const val STATUS_PENDING = "PENDING"
const val STATUS_HIGH = "HIGH"
const val STATUS_CONFLICT = "CONFLICT"
const val STATUS_MERGED = "MERGED"
const val STATUS_IGNORED = "IGNORED"
const val STATUS_FAILED = "FAILED"

const val OP_RUNNING = "RUNNING"
const val OP_DONE = "DONE"
const val OP_VERIFY_FAILED = "VERIFY_FAILED"
const val OP_FAILED = "FAILED"
const val OP_SPLIT = "SPLIT"
const val OP_SPLIT_FAILED = "SPLIT_FAILED"

const val SPLIT_UNTESTED = "UNTESTED"
const val SPLIT_SUPPORTED = "SUPPORTED"
const val SPLIT_UNSUPPORTED = "UNSUPPORTED"

/**
 * 拆分接口是否已在测试服验证。
 *
 * 官方只写了 "Removes alternate video sources."，没有承诺不删文件、也没有承诺恢复 ItemId。
 * 在阶段 0 验证完成前，这里必须保持 false —— UI 可以展示历史/快照，但绝不能调用拆分接口。
 * 验证内容见 docs/design/smart-merge-feasibility.md 第 3.3 节。
 */
const val SMART_MERGE_SPLIT_VERIFIED = false
