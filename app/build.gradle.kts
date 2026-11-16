import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号唯一来源：根目录 version.properties
// 允许 CI 用 -PversionCode=xxx 覆盖（构建号自增）
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val appVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull()
    ?: versionProps.getProperty("versionCode", "1").toInt()
val appVersionName: String = (findProperty("versionName") as String?)
    ?: versionProps.getProperty("versionName", "0.1.0")

android {
    namespace = "com.wristcontrol.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wristcontrol.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // 用独立命名的配置，不改写 AGP 内置的 debug 配置。
        // 证书固定为 app/signing/debug.p12，与手环 RPK 同源——
        // 小米互联要求 APK 与 RPK 同包名同证书，换一次证书就要两个一起重装。
        create("wristDebug") {
            storeFile = file("signing/debug.p12")
            storeType = "PKCS12"
            storePassword = "wristcontrol"
            keyAlias = "wristcontrol"
            keyPassword = "wristcontrol"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("wristDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}