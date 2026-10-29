// 云联通（M5）：Supabase Auth 手写 HTTP（不用 supabase-kt）、账号会话、「让小咪整理」受限命名空间、功能准入。
// 传输层是 HttpURLConnection（不跟随重定向、不缓存、不带 Cookie）；JVM 单测用 MockWebServer。
// 没有配置（local.properties 里没有 Supabase/后端地址）时整个模块不发任何请求，壳只走本机路径。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.heydusk.mimi.network"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig { minSdk = 29 }
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

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:domain"))
    api(project(":core:data"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
