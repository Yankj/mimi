plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.heydusk.mimi.i18n"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig {
        minSdk = 29
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests { isReturnDefaultValues = true }
    }
}

kotlin {
    jvmToolchain(21)
}

// 与 iOS 共用同一份文案目录。UIStrings.json 的唯一源码副本在 apps/ios-conversation/Resources，
// 构建时把它复制成生成的 assets 目录（不在仓库里保留第二份）。路径相对本模块。
abstract class SyncUiStringsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val target = outputDir.get().file("UIStrings.json").asFile
        target.parentFile.mkdirs()
        source.get().asFile.copyTo(target, overwrite = true)
    }
}

val syncUiStrings = tasks.register<SyncUiStringsTask>("syncUiStrings") {
    description = "把 apps/ios-conversation/Resources/UIStrings.json 复制到 assets"
    source.set(layout.projectDirectory.file("../../../ios-conversation/Resources/UIStrings.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncUiStrings, SyncUiStringsTask::outputDir)
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
