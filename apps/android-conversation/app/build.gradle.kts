import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}

/**
 * M5 云端配置只从未入库的 local.properties（或同名环境变量）读取；都没有 = 离线构建，只走本机路径。
 * 仓库里不放任何真实地址或密钥。
 */
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun cloudSetting(key: String, env: String): String =
    (localProps.getProperty(key) ?: System.getenv(env) ?: "").trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.heydusk.mimi"
    // Compose 1.12 要求 compileSdk ≥ 37；37.1 是 SDK 里实际安装的平台。
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.heydusk.mimi"
        minSdk = 29
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        buildConfigField("String", "MIMI_SUPABASE_URL", quoted(cloudSetting("mimi.supabase.url", "MIMI_SUPABASE_URL")))
        buildConfigField("String", "MIMI_SUPABASE_ANON_KEY", quoted(cloudSetting("mimi.supabase.anonKey", "MIMI_SUPABASE_ANON_KEY")))
        buildConfigField("String", "MIMI_BACKEND_ORIGIN", quoted(cloudSetting("mimi.backend.origin", "MIMI_BACKEND_ORIGIN")))
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            // M0 不签名也不发布；发布管线（签名、R8 规则验证）在 M6。
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:device"))
    implementation(project(":core:network"))
    implementation(libs.androidx.browser)
    implementation(project(":core:designsystem"))
    implementation(project(":core:i18n"))
    implementation(project(":core:voice"))
    implementation(project(":feature:parenting"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
