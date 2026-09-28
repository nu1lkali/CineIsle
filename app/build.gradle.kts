import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    versionCode = 131
    versionName = "1.3.1"

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

  buildTypes {
    named("release") {
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
      "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    )
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

composeCompiler {
  includeSourceInformation = true
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

    implementation(libs.truetype.parser)
    implementation(libs.fsaf)
    implementation(libs.mediainfo.lib)
    implementation(libs.mpv.android)

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

fun getCommitCount(): String =
  runCommand("git rev-list --count HEAD") ?: "0"

fun getCommitSha(): String =
  runCommand("git rev-parse --short HEAD") ?: "unknown"

fun runCommand(command: String): String? =
  try {
    val parts = command.split(' ')
    val process = ProcessBuilder(parts)
      .redirectErrorStream(true)
      .start()

    val output = process.inputStream
      .bufferedReader()
      .readText()
      .trim()

    process.waitFor()
    output.ifEmpty { null }
  } catch (e: Exception) {
    null
  }
