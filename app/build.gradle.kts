plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "dev.weixiao.wxfilemanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.weixiao.wxfilemanager"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            pickFirsts += listOf("libvlc.so", "libvlcjni.so", "libc++_shared.so")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // SMB
    implementation(libs.smbj)
    implementation(libs.dcerpc)
    implementation(libs.gson)
    
    // Image Loading
    implementation(libs.glide)
    
    // Video Playback
    implementation(libs.libvlc.all)
    
    // FFmpeg thumbnail (supports Hi10P, 10-bit formats)
    implementation(libs.ffmpegretriever.core)
    implementation(libs.ffmpegretriever.native)
    
    // Coroutines
    implementation(libs.coroutines.android)
    
    // Lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    
    // Navigation
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    
    // UI
    implementation(libs.recyclerview)
    implementation(libs.swiperefreshlayout)

    // Charset Detection
    implementation(libs.juniversalchardet)

    // Encrypted Storage
    implementation(libs.androidx.security.crypto)

    // Parcelize: AGP 9 内置 Kotlin 不会自动连接 parcelize Gradle 插件的 compiler classpath，
    // 因此需要手动注入 compiler plugin jar，并把 runtime 注解 jar 作为 compileOnly 依赖。
    "kotlinCompilerPluginClasspath"(libs.kotlin.parcelize.compiler)
    compileOnly(libs.kotlin.parcelize.runtime)

    // Syntax Highlighting
    implementation(libs.prism4j) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    annotationProcessor(libs.prism4j.bundler) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}