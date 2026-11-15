// PC에서 실행하는 호출어 성능 평가 도구 (앱에 포함되지 않음).
// 실행: gradlew --offline :wakeword-eval:run --args="wakeword-eval/data/tts"
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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
    implementation(project(":wakeword-phone"))
}

application {
    mainClass.set("com.mycarhub.wakeword.eval.EvalKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
}
