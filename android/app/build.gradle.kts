plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // Flutter 引擎的 Dart 资源（debug 是 kernel_blob.bin，release 是 libapp.so 旁边的产物）。
    // add-to-app 正常由 include_flutter.groovy 自动接上；本工程手动接线，需要在宿主里
    // 显式把模块的 flutter_assets 目录并进来，否则装上去是白屏（APK 里没有 flutter_assets）。
    sourceSets {
        getByName("main") {
            assets.srcDir(
                rootProject.projectDir.parentFile.resolve(
                    "flutter_ui/.android/Flutter/build/intermediates/flutter/debug",
                ),
            )
            assets.srcDir(
                rootProject.projectDir.parentFile.resolve(
                    "flutter_ui/.android/Flutter/build/intermediates/flutter/release",
                ),
            )
        }
    }
    namespace = "com.echolink.app"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.echolink.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.6.0"

        // 四种 ABI 全打：
        // - arm64-v8a：主流手机（性能最好，Oboe 低延迟路径最完整）
        // - armeabi-v7a：老 32 位手机 / 廉价平板
        // - x86_64：模拟器与少数平板（也方便在没有真机时验证界面）
        // 原生代码是纯 C++17，没有架构相关分支；Oboe prefab 四种都提供。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 调试包允许的额外日志，与 release 一致的低延迟参数。
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Flutter UI（add-to-app）：界面用 Flutter 画，音频链路仍在 Kotlin/C++ 侧
    implementation(project(":flutter"))

    // Oboe 通过 prefab 提供 CMake 里的 oboe::oboe 目标
    implementation("com.google.oboe:oboe:1.9.3")

    testImplementation("junit:junit:4.13.2")
    // Android SDK 里的 org.json 在单元测试中是桩实现（调用即抛 "Stub!"），
    // 想在 JVM 上真正验证 JSON 解析，必须引入官方实现。
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// 让资源合并显式依赖 Flutter 的编译任务，否则 Gradle 会报
// "uses this output ... without declaring an explicit or implicit dependency"。
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(":flutter:compileFlutterBuildDebug")
}