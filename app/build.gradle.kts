plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wristcontrol.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wristcontrol.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
