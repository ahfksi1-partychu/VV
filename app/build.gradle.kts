import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// local.properties (커밋 안 함): 동기화 서버 주소·공개 키
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.mycarhub"
    compileSdk = 34

    // SDK에 포함된 android.car.jar (주행 제한 API) — 추가 다운로드 없음
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.mycarhub"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"

        // 차량 ↔ 휴대폰 동기화 서버 (Supabase). local.properties(커밋 안 함)의 값만 쓴다. 비어 있으면 동기화 기능이 꺼진다.
        // 공개(publishable) 키만 넣는다 — service_role 키는 절대 넣지 않는다.
        fun quoted(name: String) = "\"" + localProps.getProperty(name, "").trim().replace("\\", "").replace("\"", "") + "\""
        buildConfigField("String", "SUPABASE_URL", quoted("supabase.url"))
        buildConfigField("String", "SUPABASE_KEY", quoted("supabase.anonKey"))
    }

    buildTypes {
        debug {
            // 실제 목소리 측정 화면 (src/debug 에만 있음)
            buildConfigField("String", "MEASUREMENT_ACTIVITY", "\"com.mycarhub.debug.MeasurementActivity\"")
        }
        release {
            buildConfigField("String", "MEASUREMENT_ACTIVITY", "\"\"")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // car: Pleos 공모전 제출용 (기존 그대로, 기본값)
    // phone: 일반 스마트폰 테스트용. 코드는 같고 src/phone 의 manifest·리소스만 덮어쓴다.
    flavorDimensions += "platform"
    productFlavors {
        create("car") {
            dimension = "platform"
            isDefault = true
        }
        create("phone") {
            dimension = "platform"
            // 차량용과 함께 설치되어도 데이터가 섞이지 않도록 별도 패키지
            applicationIdSuffix = ".phone"
            versionNameSuffix = "-phone"
            // 유튜브 재생 명령용 YouTube Data API 키 (local.properties의 youtube.apiKey, 커밋 안 함). 비어 있으면 재생 없이 검색 결과만 연다.
            buildConfigField("String", "YOUTUBE_API_KEY", "\"" + localProps.getProperty("youtube.apiKey", "").trim().replace("\\", "").replace("\"", "") + "\"")
        }
    }

    buildFeatures {
        // 개발 빌드에서만 테스트 오디오 입력 허용 (BuildConfig.DEBUG)
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 개인 호출어 등록·감지 (MFCC + DTW, 외부 라이브러리 없음)
    implementation(project(":wakeword-core"))

    // 음성 명령 해석·앱 이름 매칭 (규칙 기반, 외부 라이브러리 없음)
    implementation(project(":assistant-core"))

    // 폰 빌드만: 배경 소음 추정을 보완한 호출어 감지기 (차량용 빌드에는 포함되지 않음)
    "phoneImplementation"(project(":wakeword-phone"))

    // 폰 빌드만: 비슷한 이름 찾기(초성·중성·종성 발음 유사도)와 사람 이름이 든 메시지 명령 해석
    "phoneImplementation"(project(":assistant-phone"))

    // Pleos Connect SDK — 다운로드 승인 후 활성화
    // implementation("ai.pleos.playground:Vehicle:2.3.2")
    // implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
