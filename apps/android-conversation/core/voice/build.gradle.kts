// 语音听写：SpeechRecognizer 封装、分段/重试状态机、按住手势的会话控制器。
// 状态机与控制器是纯 Kotlin（JVM 单测）；只有 AndroidSpeechSegmentSource 碰平台 API，放在隔离的文件里。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.heydusk.mimi.voice"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    // 真机上的识别器可用性检查（箱子上起不了模拟器，只编译；见 dist/PHONE-CHECKLIST.md）。
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.test.junit)
}
