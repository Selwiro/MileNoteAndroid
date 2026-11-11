import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.milenote.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.milenote.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 97
        versionName = "0.38.0"
        vectorDrawables.useSupportLibrary = true

        // 只带 ARM 的 native 库：Tesseract4Android 同样按 ABI 分发 .so，x86 只用于模拟器调试，
        // 不进正式包；本机模拟器是 arm64 镜像，无需 x86 也能跑。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 发布签名可选：口令与密钥都不入库。提供 keystore/keystore.properties 或
    // MILENOTE_STORE_PASSWORD / MILENOTE_KEY_PASSWORD 时才启用签名，否则 assembleRelease 产出未签名 APK。
    signingConfigs {
        create("release") {
            val keystoreProps = Properties()
            val propsFile = rootProject.file("keystore/keystore.properties")
            if (propsFile.exists()) propsFile.inputStream().use { stream -> keystoreProps.load(stream) }
            storeFile = rootProject.file(
                keystoreProps.getProperty("storeFile") ?: "keystore/milenote-release.jks"
            )
            storePassword = keystoreProps.getProperty("storePassword")
                ?: System.getenv("MILENOTE_STORE_PASSWORD")
            keyAlias = keystoreProps.getProperty("keyAlias") ?: "milenote"
            keyPassword = keystoreProps.getProperty("keyPassword")
                ?: System.getenv("MILENOTE_KEY_PASSWORD")
        }
    }

    // 只有密钥文件存在且口令齐全才给 release 挂签名；否则产出 app-release-unsigned.apk，保证任何人 clone 后能构建。
    val releaseSigningConfig = signingConfigs.getByName("release")
    val hasReleaseSigning = releaseSigningConfig.storeFile?.exists() == true &&
        !releaseSigningConfig.storePassword.isNullOrBlank() &&
        !releaseSigningConfig.keyPassword.isNullOrBlank()

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) releaseSigningConfig else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.cz.adaptech.tesseract4android)

    implementation(libs.io.coil.coil.compose)
    implementation(libs.io.coil.coil.network.okhttp)
    implementation(libs.com.squareup.okhttp)
    implementation(libs.org.jetbrains.kotlinx.serialization.json)
    implementation(libs.org.jetbrains.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.jetbrains.kotlinx.coroutines.test)
}
