// 폰 빌드 전용 음성 명령 보완 (순수 Kotlin/JVM). 차량용(car) 빌드에는 포함되지 않는다.
// assistant-core는 차량용 APK와 공유하므로 그대로 두고, 사람 이름으로 메시지를 찾는 기능을 여기에 둔다.
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
    implementation(project(":assistant-core"))
}

// 오프라인 환경이라 JUnit 없이 main 기반 자체 검사를 돌린다: gradlew --offline :assistant-phone:selfTest
sourceSets {
    create("selfTest") {
        compileClasspath += sourceSets.main.get().output + configurations["runtimeClasspath"]
        runtimeClasspath += sourceSets.main.get().output + configurations["runtimeClasspath"]
    }
}

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    classpath = sourceSets["selfTest"].runtimeClasspath
    mainClass.set("com.mycarhub.assistant.phone.SelfTestKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
}
