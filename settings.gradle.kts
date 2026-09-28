pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
  dependencyResolutionManagement {
    // 改为 PREFER_PROJECT：允许 init.gradle 注入阿里云镜像（国内构建加速），
    // 原 mpvEx 用 FAIL_ON_PROJECT_REPOS 严格模式拒绝 init 脚本加 repo。
    // 二开版放宽以便本机 init.gradle 的 maven.aliyun.com 镜像生效。
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
      google()
      mavenCentral()
      maven(url = "https://www.jitpack.io") {
        content {
          // Only use JitPack for specific dependencies to avoid unnecessary checks
          includeGroup("io.github.abdallahmehiz")
          includeGroup("com.github.abdallahmehiz")
          includeGroup("com.github.K1rakishou")
          includeGroup("com.github.thegrizzlylabs")
          includeGroup("com.github.nanihadesuka")
        }
      }
    }
  }

rootProject.name = "mpvEx"
include(":app")
