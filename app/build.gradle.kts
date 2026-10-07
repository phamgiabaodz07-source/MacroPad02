plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.macropad.next"
    compileSdk = 34

    // Chữ ký cố định: mọi bản build đều cùng chữ ký nên cài đè (cập nhật) được
    signingConfigs {
        getByName("debug") {
            storeFile = file("macropad.jks")
            storePassword = "android"
            keyAlias = "macropad"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.macropad.next"
        minSdk = 30
        targetSdk = 34
        // Số phiên bản tự tăng theo thời gian build, luôn lớn hơn bản trước
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "2.0-step1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
