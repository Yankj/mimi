// 设备上的持久化：加密育儿账本（Android Keystore AES-GCM）与 DataStore 偏好。
// 不依赖 Hilt（依赖注入只在 :app 里装配）；纯 Kotlin 部分用普通 JVM 单测，Keystore 部分用 Robolectric。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.heydusk.mimi.data"
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
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    // 真机/模拟器上的 Keystore 测试（箱子上起不了模拟器，只编译；见 README）。
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
