// AGP/Kotlin 버전은 로컬 Gradle 캐시에 이미 있는 조합으로 맞춤 (추가 다운로드 최소화)
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.11.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
    }
}
