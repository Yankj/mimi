// 纯 JVM 模块：不依赖 Android，业务规则与偏好模型放这里，在 Linux 上直接跑单测。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 账本模型要能序列化（加密账本里存的就是它们）；序列化库本身是纯 JVM，不引入 Android。
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
