// 폰 빌드 전용 호출어 감지 보완 (순수 Kotlin/JVM). 차량용(car) 빌드에는 포함되지 않는다.
// wakeword-core는 차량용 APK와 공유하므로 그대로 두고, 폰에서 확인된 문제의 수정본을 여기에 둔다.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":wakeword-core"))
}
