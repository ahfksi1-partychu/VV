// 음성 명령 해석·앱 이름 매칭 (순수 Kotlin/JVM). Android 앱이 사용하고, PC에서 selfTest로 검증한다.
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

// 오프라인 환경이라 JUnit 없이 main 기반 자체 검사를 돌린다: gradlew --offline :assistant-core:selfTest
sourceSets {
    create("selfTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    classpath = sourceSets["selfTest"].runtimeClasspath
    mainClass.set("com.mycarhub.assistant.core.SelfTestKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
}

// 실제 Supabase 서버 검사 (네트워크 사용, local.properties 의 supabase.url·anonKey): gradlew --offline :assistant-core:syncLive
tasks.register<JavaExec>("syncLive") {
    group = "verification"
    classpath = sourceSets["selfTest"].runtimeClasspath
    mainClass.set("com.mycarhub.assistant.core.SyncLiveKt")
    args(rootProject.file("local.properties").absolutePath)
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
}
