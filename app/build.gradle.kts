import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "jb.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "jb.companion"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_static")
            }
        }
    }
    signingConfigs {
        create("release") {
            val props = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) load(f.inputStream())
            }
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: props.getProperty("signing.storePath", "keystore.p12"))
            storeType = "PKCS12"
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: props.getProperty("signing.storePassword", "android")
            keyAlias = System.getenv("KEY_ALIAS") ?: props.getProperty("signing.keyAlias", "releasekey")
            keyPassword = System.getenv("KEY_PASSWORD") ?: props.getProperty("signing.keyPassword", "android")
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }
    androidResources {
        noCompress.add("apk")
        noCompress.add("ko")
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    flavorDimensions += "appType"
    productFlavors {
        create("bootstrap") {
            dimension = "appType"
        }
        create("system") {
            dimension = "appType"
        }
    }
}
dependencies {
    implementation(libs.libsu.core)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.activity.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
