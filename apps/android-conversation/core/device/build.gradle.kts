// 本机提醒：通知渠道、POST_NOTIFICATIONS 权限流程、AlarmManager 调度、开机/时区/校时后的重排触发。
// 调度器与规则（ParentingReminderScheduler）是纯 Kotlin，盖在 ReminderNotificationCenter 接口上，JVM 里用假中心测；
// 只有 AlarmNotificationCenter / 接收器 / 渠道碰平台 API。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.heydusk.mimi.device"
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
    api(project(":core:i18n"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    // 真机上的通知/闹钟能力检查（箱子上起不了模拟器，只编译；见 dist/PHONE-CHECKLIST.md）。
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.test.junit)
}
