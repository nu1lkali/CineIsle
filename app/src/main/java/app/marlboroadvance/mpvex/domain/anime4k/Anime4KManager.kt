package app.marlboroadvance.mpvex.domain.anime4k

import android.content.Context

import java.io.File
import java.io.FileOutputStream

/**
 * Anime4K Manager
 * Manages GLSL shaders for real-time anime upscaling
 */
class Anime4KManager(private val context: Context) {

  companion object {
    private const val SHADER_DIR = "shaders"

    /**
     * 「这批 shader 已经拷完」的标记文件名。
     *
     * 放在 shader 目录里，和 shader 本身同生共死 ——
     * 用户清数据 / 换存储位置时它跟着一起没，下次自然会重拷一遍。
     *
     * 为什么需要它（清单 #20 必修 2）：[initialize] 每次冷启动都要
     * `assets.list()` + 逐个 `assets.open()`，Anime4K 有几十个 .glsl，
     * 而调用点 `MPVView.initOptions()` 在**主线程**（mpv 初始化必须在主线程）。
     * 哪怕 `copyShaderFromAssets` 已经会跳过已存在的文件，
     * 「读assets 目录 + 逐个 stat 几十个文件」本身也不该压在 UI 线程上。
     */
    private const val DONE_MARKER = ".assets_copied"
  }

  // Shader quality levels
  enum class Quality(val suffix: String, val titleRes: Int) {
    FAST("S", app.marlboroadvance.mpvex.R.string.anime4k_quality_fast),
    BALANCED("M", app.marlboroadvance.mpvex.R.string.anime4k_quality_balanced),
    HIGH("L", app.marlboroadvance.mpvex.R.string.anime4k_quality_high)
  }

  // Anime4K modes
  enum class Mode(val titleRes: Int) {
    OFF(app.marlboroadvance.mpvex.R.string.anime4k_mode_off),
    A(app.marlboroadvance.mpvex.R.string.anime4k_mode_a),
    B(app.marlboroadvance.mpvex.R.string.anime4k_mode_b),
    C(app.marlboroadvance.mpvex.R.string.anime4k_mode_c),
    A_PLUS(app.marlboroadvance.mpvex.R.string.anime4k_mode_a_plus),
    B_PLUS(app.marlboroadvance.mpvex.R.string.anime4k_mode_b_plus),
    C_PLUS(app.marlboroadvance.mpvex.R.string.anime4k_mode_c_plus)
  }

  private var shaderDir: File? = null
  private var isInitialized = false

  /**
   * 纯 IO 的一步：把 shader 从 assets 拷到内部存储。
   *
   * **必须在 IO 线程调用**（清单 #20 必修 2）。
   * 真正写 mpv 选项的 [applyPendingChain] 才需要在主线程。
   *
   * 幂等：已拷完（有标记文件）时只做一次 `File.exists()` 判断就返回，
   * 连 `assets.list()` 都不进。
   */
  fun prepareOnIoThread(): Boolean {
    if (isInitialized) return true

    return try {
      val dir = File(context.filesDir, SHADER_DIR)
      if (!dir.exists() && !dir.mkdirs()) return false

      // 快路径：上次已经拷完了，跳过全部 assets 访问
      if (File(dir, DONE_MARKER).exists()) {
        shaderDir = dir
        isInitialized = true
        return true
      }

      val shaderFiles = context.assets.list(SHADER_DIR) ?: emptyArray()
      for (fileName in shaderFiles) {
        if (fileName.endsWith(".glsl")) {
          copyShaderFromAssets(fileName, dir)
        }
      }

      // 标记落盘放在最后：中途崩了 / 被 kill，下次会重来一遍，
      // 绝不会出现「标记说有、其实没拷全」的状态。
      runCatching { File(dir, DONE_MARKER).writeText("") }

      shaderDir = dir
      isInitialized = true
      true
    } catch (e: Exception) {
      isInitialized = false
      false
    }
  }

  /**
   * Initialize: copy shaders from assets to internal storage
   * This must be called and complete successfully before using getShaderChain()
   *
   * ⚠️ 保留这个入口是为了不破坏既有调用点，但它**内部就是IO**，
   * 别在主线程调（清单 #20 必修 2）；正确姿势是先在 IO 线程
   * [prepareOnIoThread]，再在主线程 [resolveChain]。
   */
  fun initialize(): Boolean = prepareOnIoThread()

  /**
   * 主线程侧：把已备好的 shader 链路解析出来。
   *
   * **不写任何 mpv 选项** —— 那是 [app.marlboroadvance.mpvex.ui.player.MPVView] 的活，
   * 保持本类「不依赖 mpv」，这样整段逻辑都能在本地 JVM 单测里跑。
   *
   * @param isEnabled 用户是否开了 Anime4K
   * @param gpuNextActive / useVulkan 用于复现调用方的兼容性前置判断
   * @return 冒号分隔的 shader 绝对路径链；不该着色时返回空串
   */
  fun resolveChain(
    isEnabled: Boolean,
    gpuNextActive: Boolean,
    useVulkan: Boolean,
    modeStr: String,
    qualityStr: String,
  ): String {
    if (!isEnabled) return ""

    // Anime4K 要求legacy GPU 路径，除非 gpu-next 跑在 Vulkan 上。
    // 不满足就整段放弃，避免塞进不兼容的状态。
    if (gpuNextActive && !useVulkan) return ""

    // 典型情况：prepareOnIoThread 没来得及跑（或失败了）。
    // 此时**不能**在这里同步拷贝 —— 那是主线程 IO，正是清单 #20 要消除的东西。
    // 宁可这一集没着色，也别卡 UI。
    if (!isInitialized) return ""

    if (modeStr == "OFF") return ""

    val mode = try {
      Mode.valueOf(modeStr)
    } catch (e: IllegalArgumentException) {
      return ""
    }

    val quality = try {
      Quality.valueOf(qualityStr)
    } catch (e: IllegalArgumentException) {
      Quality.BALANCED
    }

    return getShaderChain(mode, quality)
  }

  private fun copyShaderFromAssets(fileName: String, dir: File): Boolean {
    val destFile = File(dir, fileName)

    // Skip if file already exists and is valid
    if (destFile.exists() && destFile.length() > 0) {
      return false
    }

    return try {
      context.assets.open("$SHADER_DIR/$fileName").use { input ->
        FileOutputStream(destFile).use { output ->
          input.copyTo(output)
        }
      }
      true
    } catch (e: Exception) {
      false
    }
  }

  private val shaderDirOrNull: File?
    get() = shaderDir

  /**
   * Get shader chain for the specified mode and quality
   * Returns empty string if mode is OFF or initialization failed
   */
  fun getShaderChain(mode: Mode, quality: Quality): String {
    if (mode == Mode.OFF) {
      return ""
    }

    if (!isInitialized) {
      return ""
    }

    // 原来写的是 `shaderDir == null || !shaderDir!!.exists()` ——
    // 短路求值救了我们一次（为 null 时不会走到 `!!`），但 `!!` 在这里纯属多余：
    // 一个可能为 null 的可空字段本来就不该用 `!!`。
    val dir = shaderDir ?: return ""
    if (!dir.exists()) {
      return ""
    }

    val shaders = mutableListOf<String>()
    val q = quality.suffix

    // Always add Clamp_Highlights (prevent ringing)
    shaders.add(getShaderPath("Anime4K_Clamp_Highlights.glsl"))

    // Add shaders based on mode
    when (mode) {
      Mode.A -> {
        // Mode A: Restore -> Upscale -> Upscale
        shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.B -> {
        // Mode B: Restore_Soft -> Upscale -> Upscale
        shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.C -> {
        // Mode C: Upscale_Denoise -> Upscale
        shaders.add(getShaderPath("Anime4K_Upscale_Denoise_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.A_PLUS -> {
        // Mode A+A: Restore -> Upscale -> Restore -> Upscale
        shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.B_PLUS -> {
        // Mode B+B: Restore_Soft -> Upscale -> Restore_Soft -> Upscale
        shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.C_PLUS -> {
        // Mode C+A: Upscale_Denoise -> Restore -> Upscale
        shaders.add(getShaderPath("Anime4K_Upscale_Denoise_CNN_x2_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
        shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
        shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
      }
      Mode.OFF -> {
        // Already handled
      }
    }

    // Validate that all shader files exist
    val missingShaders = shaders.filter { path ->
      !File(path).exists()
    }
    
    if (missingShaders.isNotEmpty()) {
      return ""
    }

    // Join with colon separator
    val chain = shaders.joinToString(":")
    return chain
  }

  private fun getShaderPath(fileName: String): String {
    // `File(File?, String)` 这个构造器签名要求第一个参数非空，
    // 而 `shaderDir` 是可空的。这里给一个兜底 —— 走到这里说明 [getShaderChain]
    // 的前置检查已经过了，目录必然存在，所以取不到时返回的路径必然不存在，
    // 上层的「Validate that all shader files exist」会直接把它判掉。
    return File(shaderDirOrNull ?: return "", fileName).absolutePath
  }
}
