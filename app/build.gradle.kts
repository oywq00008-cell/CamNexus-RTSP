import java.util.Properties

plugins {
    id("com.android.application")
}

// 发布签名：口令放在根目录的 keystore.properties（已在 .gitignore 中排除），
// 开源时不会泄露私钥；缺少该文件时仍可正常构建 debug 包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.camnexus.rtsp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.camnexus.rtsp"
        minSdk = 21
        targetSdk = 35
        versionCode = 23
        versionName = "2.3.6"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.github.pedroSG94:RTSP-Server:1.4.3")
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
}

// RootEncoder 的 whip 模块（WebRTC 推流）会拖进整个 BouncyCastle（约 5.7MB），
// 本项目只用 RTSP，不涉及 WHIP/DTLS，直接排除。
configurations.configureEach {
    exclude(group = "org.bouncycastle")
}
