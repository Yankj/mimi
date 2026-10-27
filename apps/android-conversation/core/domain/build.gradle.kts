// 纯 JVM 模块：不依赖 Android，业务规则与偏好模型放这里，在 Linux 上直接跑单测。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}
