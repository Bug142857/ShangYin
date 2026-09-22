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
        versionCode = 1194
        versionName = "0.194"
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

    // 在线观影：ExoPlayer + HLS 流媒体播放（media3 1.5.1，兼容 compileSdk 35）
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    debugImplementation(libs.androidx.ui.tooling)
}
