plugins {
    id("com.android.application")
}

android {
    namespace = "com.litemusic"
    compileSdk = 36

    defaultConfig {
        // 发布包名；源码 namespace 仍为 com.litemusic（不影响安装身份）
        applicationId = "com.yesplaymusic.mobile"
        minSdk = 21
        targetSdk = 34
        versionCode = 33
        versionName = "3.1.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // 以下版本为兼容 minSdk 21（Android 5.0）的最低要求组合：
    // core 1.15+/appcompat 1.7+ 要求更高 minSdk，需降级
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.zxing:core:3.5.3")
}
