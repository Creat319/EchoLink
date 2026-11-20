// Flutter module 接入（add-to-app）。
//
// 官方给的是一条 Groovy 脚本（flutter_ui/.android/include_flutter.groovy），
// 但本工程的 settings 是 Kotlin DSL。这里把那条脚本做的事用 Kotlin DSL 显式写一遍：
//   1. include :flutter，指向模块的 .android/Flutter
//   2. 从模块的 local.properties 读出 flutter.sdk
//   3. 接上 Flutter 官方的 Gradle 插件（includeBuild）
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EchoLink"
include(":app")

// ---------------------------------------------------------------- Flutter module
val flutterModuleDir = File(rootDir.parentFile, "flutter_ui")

if (flutterModuleDir.resolve("pubspec.yaml").exists()) {
    val modulePropertiesFile = flutterModuleDir.resolve(".android/local.properties")
    val flutterSdkPath = java.util.Properties().apply {
        modulePropertiesFile.reader(Charsets.UTF_8).use { load(it) }
    }.getProperty("flutter.sdk")
        ?: throw GradleException(
            "flutter.sdk 未设置。请先在 ${flutterModuleDir.path} 下执行 flutter pub get。",
        )

    include(":flutter")
    project(":flutter").projectDir = flutterModuleDir.resolve(".android/Flutter")

    pluginManagement {
        includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")
    }
} else {
    logger.lifecycle("未找到 flutter_ui 模块，本次构建不包含 Flutter UI。")
}
