import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose.compiler)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
  alias(libs.plugins.aboutlibraries)
}

android {
  namespace = "app.marlboroadvance.mpvex"
  compileSdk = 37

  // 开发期快速出包开关（只打单个 ABI 包，避免 4×151MB 原生库合并把构建拖超时）：
  //   -Pabi=x86_64      只出 x86_64 单包（模拟器用这个）
  //   -Pabi=arm64-v8a   只出 arm64 单包（真机用这个）
  //   -Parm64Only       等价于 -Pabi=arm64-v8a（保留旧用法，向后兼容）
  // 不带任何参数时，构建行为与原来完全一致（4 个 ABI 拆包 + universal）。
  val singleAbi: String? =
    (project.findProperty("abi") as String?)?.takeIf { it.isNotBlank() }
      ?: if (project.hasProperty("arm64Only")) "arm64-v8a" else null

  defaultConfig {
    // 应用标识（安装包名）：不再沿用上游的 app.marlboroadvance.mpvex，
    // 改为项目自身的 app.cineisle.player。namespace 保持不动 ——
    // 它只决定 R / BuildConfig 的生成包名，改名会牵动全量源码，收益为零。
    applicationId = "app.cineisle.player"
    minSdk = 26
    targetSdk = 36
    // versionCode 决定能否覆盖安装：每发一版都要涨（APK 里的最终值 = versionCode * 10 + ABI 码）
    versionCode = 4
    versionName = "1.0.7"

    vectorDrawables {
      useSupportLibrary = true
    }

    buildConfigField("String", "GIT_SHA", "\"${getCommitSha()}\"")
    buildConfigField("int", "GIT_COUNT", getCommitCount())
  }

  flavorDimensions += "distribution"

  productFlavors {
    create("standard") {
      dimension = "distribution"
      isDefault = true
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "true")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")
    }

    create("playstore") {
      dimension = "distribution"
      versionNameSuffix = "-playstore"
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "false")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "true")
    }

    create("fdroid") {
      dimension = "distribution"
      versionNameSuffix = "-fdroid"
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "false")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")

      ndk {
        // 与 splits 的 ABI 过滤互斥：单包模式（-Pabi / -Parm64Only）下必须留空，否则 AGP 直接报配置冲突
        if (singleAbi == null) abiFilters += "arm64-v8a"
      }
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  splits {
    abi {
      isEnable = true
      reset()
      if (singleAbi != null) {
        include(singleAbi)
        isUniversalApk = false
      } else {
        include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        isUniversalApk = true
      }
    }
  }

  // ── Release 签名 ──
  // 私钥路径与口令统一放在仓库根的 keystore.properties（.gitignore 已屏蔽），
  // 避免把口令硬编码进构建脚本。该文件缺失时（例如 CI 只跑 assembleDebug / lint）
  // 不抛异常，仅表现为 release 未签名，不让「有没有签名」变成「能不能构建」的硬依赖。
  val keystorePropsFile = rootProject.file("keystore.properties")
  val keystoreProps = Properties()
  if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
  }
  val releaseKeystoreFile: File? =
    keystoreProps.getProperty("storeFile")?.trim()?.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
  val hasReleaseKeystore = releaseKeystoreFile?.exists() == true

  if (!hasReleaseKeystore) {
    logger.lifecycle(
      "[CineIsle] 未找到 keystore.properties 或 jks 文件，release 变体将产出未签名 APK。"
    )
  }

  signingConfigs {
    if (hasReleaseKeystore) {
      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
        // minSdk 26 → V2 已全线支持，无需 V1（省掉 META-INF 里的冗余签名文件）。
        enableV1Signing = false
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    named("release") {
      signingConfig = signingConfigs.findByName("release")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      ndk {
        debugSymbolLevel = "none"
      }
    }

    create("preview") {
      initWith(getByName("release"))
      signingConfig = null
      applicationIdSuffix = ".preview"
      versionNameSuffix = "-${getCommitCount()}"
    }

    named("debug") {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-${getCommitCount()}"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    viewBinding = true
    buildConfig = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "META-INF/DEPENDENCIES"
      excludes += "META-INF/LICENSE*"
      excludes += "META-INF/NOTICE*"
      excludes += "META-INF/*.kotlin_module"
      excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
    jniLibs {
      useLegacyPackaging = true
    }
  }

  @Suppress("UnstableApiUsage")
  androidResources {
    generateLocaleConfig = true
  }
}

// ── 构建提速：默认关掉 release 的 lintVital ──
//
// 实测（本机 6 核 i7-8750H）assembleStandardRelease 任务耗时分布：
//   lintVitalAnalyzeStandardRelease   7m02s  ← 比 R8 还慢
//   minifyStandardReleaseWithR8       6m31s
//   compileJavaWithJavac                54s
//   其余全部                          ~3m
// lintVital 是 AGP 给 release 变体加的「致命问题」静态检查，跑在 R8 之后、
// 且几乎吃不掉并行度（它和 R8 抢 CPU），是本机构建最大的一块纯开销。
// 日常出包关掉它；要上线前体检时加 -PlintVital=true 跑一次即可。
val lintVitalEnabled = providers.gradleProperty("lintVital").map { it.toBoolean() }.orElse(false)
tasks.matching { it.name.startsWith("lintVital") }.configureEach {
  enabled = lintVitalEnabled.get()
}

androidComponents {
  val abiCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4
  )

  onVariants { variant ->
    variant.outputs.forEach { output ->
      val abi = output.filters
        .find { it.filterType == FilterConfiguration.FilterType.ABI }
        ?.identifier

      output.versionCode.set(
        (output.versionCode.orNull ?: 0) * 10 + (abiCodes[abi] ?: 0)
      )
    }
  }
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-Xcontext-parameters",
      "-Xannotation-default-target=param-property",
      "-opt-in=com.google.accompanist.permissions.ExperimentalPermissionsApi",
      "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
      // 视界流翻页要用 VerticalPager（来自 androidx.compose.foundation）
      "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
    )
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

composeCompiler {
  // 源信息（把重组对应回源码行号）只有 debug 的 Compose 工具链用得上。
  // release 打开会给每个 composable 塞额外字符串元数据：Compose 编译器更慢、
  // R8 要处理的代码量更大（R8 占 release 构建 6m31s），产物也更大。
  includeSourceInformation = false
}

room {
  schemaDirectory("$projectDir/schemas")
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3.android)
  implementation("com.google.android.material:material:1.13.0")
  implementation(libs.androidx.compose.material)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)
  implementation(libs.bundles.compose.navigation3)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.compose.constraintlayout)
  implementation("androidx.preference:preference-ktx:1.2.1")
  implementation("androidx.constraintlayout:constraintlayout:2.2.0")
  implementation(libs.androidx.material3.icons.extended)
  implementation(libs.androidx.compose.animation.graphics)
  // 视界流（仿抖音竖屏连播）：ExoPlayer 多实例 + Compose VerticalPager。
  // foundation 显式声明是为了 VerticalPager —— 原来它是靠 ui 传递进来的，
  // 现在翻页是视界流的核心路径，不该依赖传递关系。
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.mediasession)
  implementation(libs.androidx.documentfile)
  implementation(libs.saveable)

  implementation(platform(libs.koin.bom))
  implementation(libs.bundles.koin)

  implementation(libs.seeker)
  implementation(libs.compose.prefs)
  implementation(libs.aboutlibraries.compose.m3)

  implementation(libs.accompanist.permissions)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.room.ktx)

  implementation(libs.kotlinx.immutable.collections)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  // DLNA 投屏：UPnPCast 源码内置在 app/src/main/java/com/yinnho/upnpcast/ 下（同 embyclient 的做法），
  // 版本为上游 master / v1.3.0。
  // 为什么不用 Maven Central 的 com.yinnho.upnpcast:upnpcast:1.1.2：
  //   1.1.2 的 SSDP 有致命缺陷 —— SO_REUSEADDR 在 bind 之后才设置（等于没设）、1900 端口被占就再
  //   也搜不到设备、去重表跨搜索不清空（第二次搜索起永远返回空列表，必须重启 App）、
  //   search() 一发现首个设备就返回导致列表不全、location 无端口时拼出 http://host:-1/...、
  //   本地文件一律用 application/octet-stream（部分电视直接拒收）。
  //   这些正是 1.2.0 的修复项（upstream CHANGELOG 逐条对应：discovery #1 / MulticastLock /
  //   Control URL / search() semantics / MIME by extension）。1.3.0 只上了 JitPack，
  //   Maven Central 仍停留在 1.1.2，所以直接内置源码。
  // 运行时依赖：kotlinx.coroutines + nanohttpd（均已在本文件声明）。

    implementation(libs.truetype.parser)
    implementation(libs.fsaf)
    implementation(libs.mediainfo.lib)
    implementation(libs.mpv.android)

    // GSYVideoPlayer —— 双播放内核中的第二个内核（默认仍是 mpv）
    // java = 播放器 View + IJK Java 绑定；exo2 = GSY 的 ExoPlayer 内核；
    // arm64 / armv7a / x86 / x64 = IJK 的原生 so（按 ABI 拆包，缺了 IJK 起不来）。
    implementation(libs.gsy.java)
    implementation(libs.gsy.exo2)
    implementation(libs.gsy.arm64)
    implementation(libs.gsy.armv7a)
    implementation(libs.gsy.x86)
    implementation(libs.gsy.x64)

    // Network protocol libraries
    implementation(libs.smbj)
    implementation(libs.commons.net)
    implementation(libs.sardine.android) {
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation(libs.nanohttpd)
    implementation(libs.lazycolumnscrollbar)
    implementation(libs.reorderable)

    // Emby Java SDK (OpenAPI 生成) 依赖：源码已复制到 app/src/main/java/embyclient/，
    // 由主项目 sourceSet 直接编译。SDK 基于 OkHttp2 + Gson + gson-fire + swagger-annotations。
    // 注意：OkHttp2 用包名 com.squareup.okhttp.*，与项目主用的 OkHttp3 (okhttp3.*) 不冲突可共存。
    implementation("com.squareup.okhttp:okhttp:2.7.5")
    implementation("com.squareup.okhttp:logging-interceptor:2.7.5")
    implementation("com.google.code.gson:gson:2.8.1")
    implementation("io.gsonfire:gson-fire:1.8.3")
    implementation("io.swagger.core.v3:swagger-annotations:2.0.0")
    // javax.annotation.* 在 Android 上缺失，需补 jsr250-api（SDK 代码用 @Generated 等注解）
    implementation("javax.annotation:jsr250-api:1.0")
}

/* ---------------- Git helpers ---------------- */

/**
 * 本机可用的 git 可执行文件（找不到就是 null）。
 *
 * 为什么要先找绝对路径：本机 git **不在 PATH 上**（只有 GitHub Desktop 自带的那份），
 * 直接 exec "git" 必然起不来；而配置期起外部进程失败会让 Gradle 9 判定配置缓存无效
 * （实测报 "failed to compute value with custom source ... starting process 'command 'git''"，
 * 缓存每次被丢弃）。所以只在**确认文件存在**时才真的调用。
 */
fun findGitExecutable(): String? {
  val candidates = mutableListOf<String>()
  System.getenv("GIT_EXE")?.let(candidates::add)
  val ghRoot = System.getenv("LOCALAPPDATA")?.let { File(it, "GitHubDesktop") }
  ghRoot
    ?.listFiles()
    ?.filter { it.isDirectory && it.name.startsWith("app-") }
    ?.forEach { appDir ->
      candidates += File(appDir, "resources/app/git/cmd/git.exe").absolutePath
    }
  return candidates.firstOrNull { File(it).exists() }
}

/** 提交数（用作 versionCode / debug 版本号后缀）；拿不到就退回 0 */
fun getCommitCount(): String = runGit("rev-list", "--count", "HEAD") ?: "0"

/** 短提交号（写进 BuildConfig.GIT_SHA）；拿不到就退回 unknown */
fun getCommitSha(): String = runGit("rev-parse", "--short", "HEAD") ?: "unknown"

/**
 * 用 providers.exec 而不是 ProcessBuilder 起 git。
 *
 * providers.exec 会被 Gradle 当构建输入跟踪，配置缓存能正常命中；
 * 而 ProcessBuilder 属于「配置期外部进程」，Gradle 9 直接判缓存无效。
 */
fun runGit(vararg args: String): String? {
  val exe = findGitExecutable() ?: return null
  return try {
    providers.exec {
      commandLine(listOf(exe) + args)
      // 不在 git 仓库时不要让构建失败
      isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
  } catch (e: Exception) {
    null
  }
}
