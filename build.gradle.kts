import java.util.Properties

plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

// release 签名：读 keystore.properties（不存在则 release 不签名）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.taowu"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.taowu"
        minSdk = 26
        targetSdk = 37
        versionCode = 38
        versionName = "5.6"

        // FFmpeg 软解（NextLib）每个 ABI 数 MB —— 只保留真机常见的两种，
        // 砍掉只用于模拟器的 x86/x86_64，控制 APK 体积。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 源码组织：UI/ 放界面，后端/ 放后端，入口 Main.kt 在 src/main/kotlin。
    // Kotlin 包名写在文件里，与目录名无关；用 AGP 9 的 directories 追加两个源码根。
    sourceSets {
        getByName("main") {
            kotlin.directories.add("UI")
            kotlin.directories.add("后端")
        }
        getByName("test") {
            kotlin.directories.add("src/test/kotlin")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")

    // FFmpeg 软解后端（H.264/HEVC/VP8/VP9/AV1）。版本号 = media3版本-nextlib版本，
    // 必须与上面 media3 的 1.11.1 对齐。GPL-3.0，与本项目 AGPL-3.0 兼容（GPLv3 §13）。
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.20")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
}
