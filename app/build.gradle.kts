plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.shangyin.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shangyin.app"
        minSdk = 26
        targetSdk = 35
        // 版本号规则（2026-09-22 用户指定）：versionName = 上一版 + 1（不补零），从 0.190 起
        // （不再读 git 提交次数——那条规则会因"每轮 2 个提交"而每次 +2）
        // ⚠️ versionCode 固定取 1000 + N（0.190 → 1190）：必须单调递增，否则系统拒绝覆盖安装
        versionCode = 1201
        versionName = "0.201"

        // FFmpeg 解码扩展（NextLib）带了 4 个 ABI 的原生库：只保留真机在用的两个，控制体积
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 正式版签名（为便于用户在手机上直接安装，使用稳定的 release 签名）
    // release 和 debug 共享同一 keystore，避免覆盖安装时签名冲突
    signingConfigs {
        create("release") {
            val store = rootProject.file("app/lzs_release.jks")
            if (store.exists()) {
                storeFile = store
                storePassword = "laozheng2026"
                keyAlias = "lzs"
                keyPassword = "laozheng2026"
            } else {
                // 签名文件不存在：fallback到 Android 默认 debug.keystore（仍能安装，只是 debug sign）
                println("[WARN] lzs_release.jks 未找到，使用默认签名生成 release 包")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Release 正式版关闭 debuggable（默认就是 false）
            isDebuggable = false
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            // debug 也使用与 release 一致的签名，避免两种 build type 覆盖安装冲突
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // APK 文件名: 老郑分享-版本号.apk
    applicationVariants.all {
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            out.outputFileName = "老郑分享-${versionName}.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    // Compose Foundation（LazyGrid、HorizontalPager、PagerIndicator）
    implementation("androidx.compose.foundation:foundation")

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.coil.compose)
    implementation(libs.jsoup)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // 在线观影 / 电视：ExoPlayer + HLS 流媒体播放（media3 1.7.1）
    // 1.7.1 是 NextLib 预编译 FFmpeg 扩展（nextlib-media3ext:1.7.1-0.9.0）所对应的 media3 版本，两者必须配套
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")

    // FFmpeg 软件解码扩展（NextLib，预编译 AAR）：系统没有 MP2（audio/mpeg-L2）等解码器的机型靠它出声
    // ⚠️ 该库是 GPL-3.0（FFmpeg 系许可），随 App 一起分发时整个 App 需 GPL 兼容
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")

    // 音乐模块：MediaSessionService 提供后台播放 + 通知栏/锁屏控制（版本必须与上面 media3 一致）
    implementation("androidx.media3:media3-session:1.7.1")

    debugImplementation(libs.androidx.ui.tooling)
}
