plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mangacolor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mangacolor.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 不设 abiFilters（保留全部 ABI，模拟器/真机都能装）。
        // 但用 splits 按 ABI 分包，避免一个包里塞 4 套 .so 导致 133MB。
        // 分包后每个 APK 只含一个 ABI，通用包仍保留。
    }

    // ABI 分包：每个 ABI 出一个独立 APK，体积可减一半以上。
    // 用 universalApk = true 保留一个全 ABI 包（体积大但通用，适合直接分发）。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // ONNX 模型必须保持未压缩：否则 onnxruntime 初始化时要先整包解压 58.8MB，
    // 首次加载会慢 2~3 秒，还会额外占一份私有目录空间。
    androidResources {
        noCompress += "onnx"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // GAN 上色推理。GanColorizer.kt 依赖它。
    // 版本说明：模型 opset=17 / ir_version=8，1.17.0+ 全都能跑；
    // 这里用 1.20.0（与 AGP 8.5.2 / compileSdk 34 同代，兼容性最稳）。
    // 想升级直接改这一行，接口没变。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // GanColorizer.colorizeSuspend() 用 Dispatchers.Default。
    // lifecycle-runtime-ktx 已间接带进来，这里显式声明以免哪天被移掉。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

/**
 * 模型分发：58.8MB 的 ONNX 不进 git（见 app/src/main/assets/.gitignore）。
 *
 * 获取方式：
 *   · CI：由 .github/workflows/build.yml 的 curl 步骤下载到 assets/（带重试 + SHA256 校验）
 *   · 本地：手动把 manga-colorize-fp16.onnx 拷到 app/src/main/assets/
 *
 * 文件名必须是 manga-colorize-fp16.onnx，对应 GanColorizer.DEFAULT_MODEL_ASSET。
 *
 * 注：此处原有 downloadGanModel 任务，因 Kotlin DSL 中 `java` 被 Gradle 的 java 扩展
 * 遮蔽、`java.net.URL` 无法解析而编译失败；且 CI 用 curl 更可控（有重试与校验），
 * 故移除此任务。
 */

// 打包前提醒模型缺失（不 fail，保证没有模型的 PR 也能跑 CI 编译）
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    doFirst {
        val model = layout.projectDirectory.dir("src/main/assets")
            .file("manga-colorize-fp16.onnx").asFile
        if (!model.isFile) {
            logger.warn("[gan] 缺少 ${model.absolutePath}，本次 APK 不含 GAN 模型（运行时会提示无法上色）")
        }
    }
}
