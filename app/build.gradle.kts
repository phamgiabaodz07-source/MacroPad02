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
        versionName = "2.0-step2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/versions/**", "META-INF/BCKEY.*")
    }
}

dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.0")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
}

// Thư viện ADB kéo theo bản BouncyCastle "jdk15to18" trùng với bản "jdk18on" ở trên, nên loại nó đi
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
}
