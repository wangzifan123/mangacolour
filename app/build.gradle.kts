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

        // GAN 模型是 fp16 的 ONNX，约 58.8MB。如果 NDK abiFilters 未来收窄，
        // 记得这里只保留真实用到的 ABI，否则 APK 会白白多出几十 MB。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
 * 本地/CI 构建前把模型放到 app/src/main/assets/ 即可：
 *   ./gradlew downloadGanModel -PganModelUrl=https://<你的模型地址>/manga-colorize-fp16.onnx
 * 或者手动拷贝文件过去。模型缺失时 CI 仍能编译通过（APK 里没有模型，
 * 运行到 GanColorizer.init 时会给出明确报错）。
 */
tasks.register("downloadGanModel") {
    group = "gan"
    description = "下载 GAN 上色模型到 app/src/main/assets/（需 -PganModelUrl=...）"

    val assetsDir = layout.projectDirectory.dir("src/main/assets")
    val targetFile = assetsDir.file("manga-colorize-fp16.onnx")
    val sourceUrl = providers.gradleProperty("ganModelUrl").orNull
    val expected = 58L * 1024L * 1024L

    doLast {
        val out = targetFile.asFile
        if (out.isFile && out.length() >= expected) {
            logger.lifecycle("[gan] 模型已存在，跳过下载：${out.absolutePath} (${out.length() / 1024 / 1024} MB)")
            return@doLast
        }
        if (sourceUrl.isNullOrBlank()) {
            logger.warn("[gan] 未提供 -PganModelUrl，跳过。请手动把 manga-colorize-fp16.onnx 放到 ${assetsDir.asFile.absolutePath}")
            return@doLast
        }
        out.parentFile.mkdirs()
        logger.lifecycle("[gan] 下载模型 -> ${out.absolutePath}")
        java.net.URI(sourceUrl).toURL().openStream().use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        logger.lifecycle("[gan] 完成：${out.length() / 1024 / 1024} MB")
    }
}

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
