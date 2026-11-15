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
        create("debug") {
            storeFile = file("signing/debug.jks")
            storePassword = "wristcontrol"
            keyAlias = "wristcontrol"
            keyPassword = "wristcontrol"
        }
    }

    buildTypes {
        getByName("debug") {
            // 手环端 RPK 必须用同一份证书签名，否则收不到消息
            signingConfig = signingConfigs.getByName("debug")
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
    // 小米官方手机端 SDK，需自行放入 app/libs/（见 docs/BUILD.md）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
