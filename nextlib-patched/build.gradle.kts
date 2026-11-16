plugins {
  id("com.android.library")
}

android {
  namespace = "io.github.anilbeesetti.nextlib.media3ext"
  compileSdk = 37

  defaultConfig {
    minSdk = 26
    consumerProguardFiles("consumer-rules.pro")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }

  sourceSets {
    getByName("main") {
      jniLibs.srcDirs("src/main/jniLibs")
    }
  }
}

dependencies {
  api(libs.androidx.media3.exoplayer)
  implementation("androidx.annotation:annotation:1.11.0")
  implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
  compileOnly("org.checkerframework:checker-qual:3.43.0")
  testImplementation("junit:junit:4.13.2")
}
