# VV쁘이 — 내 목소리로 부르는 차량용 음성 비서

> 현대자동차 Pleos Connect 공모전 제출 프로젝트 (Android Automotive OS 앱)

**VV쁘이**는 운전자가 직접 녹음한 목소리로 부르는 개인 호출어 음성 비서입니다.
화면을 보거나 누르지 않고 앱 실행, 메시지 읽기·답장, 주차 위치 기억, 운전 중 할 일(Drive To-Do)을 처리합니다.
호출어 인식과 명령 해석은 모두 기기 안에서 이뤄지고, 음성은 외부 서버로 보내지 않습니다.

## APK 구분 (중요)

| 파일 | 대상 | 서명 |
|---|---|---|
| `apk/VV쁘이_car_release_UNSIGNED.apk` | **차량용 (공모전 제출 대상)**, Android Automotive / Pleos | **서명 없음.** 정식 release 키로 서명해야 설치할 수 있다 |
| `apk/phone/VV쁘이_phone_debug_TEST_ONLY.apk` | 일반 Android 휴대폰 (참고·시험용) | 디버그 키. 제출용이 아니다 |

- 두 APK는 같은 소스에서 빌드한 별도 flavor다. car는 `com.mycarhub`, phone은 `com.mycarhub.phone`이다.
- phone APK에만 있는 기능(플로팅 버튼, 유튜브·지도 앱 연동, 미디어 제어 등)은 **차량용 APK에 없다.**

## 핵심 기능 (차량용, 구현됨)

- **개인 호출어**: '쁘이'를 3번 녹음한다. 녹음마다 독립 템플릿으로 저장하고, 셋 중 하나만 일치해도 호출한다 (MFCC + DTW, 기기 내 처리).
- **음성 앱 실행**: "카카오톡 열어줘", "내비게이션 실행해 줘". 앱 이름·별칭·줄임말·종류로 찾는다.
- **내 앱 등록**: 자주 쓰는 앱 등록과 별칭 지정.
- **메시지 읽기·답장**: 알림 접근 권한으로 최근 메시지를 읽고 음성으로 답장한다.
- **주차 위치 자동 기록**: AAOS 표준 차량 속성(기어 P, 시동 상태)으로 주차를 감지해 위치를 저장한다. "차 어디 세웠지?"로 묻는다.
- **Drive To-Do**: 운전 중 말한 할 일을 기억하고 주차 후 정리한다.
- **차량 ↔ 휴대폰 동기화**: Supabase를 쓰고, 기록은 종단 암호화한다 (실서버 연동은 미검증).
- **주행 중 화면 제한 준수**: `distractionOptimized`, `CarUxRestrictions`.

구현 상태와 검증 수준은 [docs/FEATURES.md](docs/FEATURES.md)와 [docs/TEST_STATUS.md](docs/TEST_STATUS.md)에 있습니다.

## 향후 개발 예정 (아직 구현되지 않음)

- Pleos 전용 SDK(Vehicle, NaviHelper 등) 연동. 현재는 **AAOS 표준 API만** 사용한다.
- 차종 자동 인식
- 동일 차종 사용자 커뮤니티
- AI 차량 관리 추천

자세한 내용은 [docs/ROADMAP.md](docs/ROADMAP.md)에 있습니다.

## 프로젝트 구조

```
app/              Android 앱 (car / phone flavor)
  src/main        공통 코드 (car와 phone 모두)
  src/car         차량용 매니페스트·리소스
  src/phone       휴대폰 전용 코드·리소스
assistant-core/   명령 해석, 앱 매칭, 주차 판단, 동기화 엔진 (순수 Kotlin + 자체 시험)
assistant-phone/  휴대폰 전용 해석 (사람 이름 매칭)
wakeword-core/    호출어 엔진: MFCC, DTW, 녹음 품질 검사, 학습
wakeword-phone/   휴대폰용 적응형 호출어 감지기
wakeword-eval/    호출어 평가 도구 (개발용)
supabase/         동기화 서버 스키마 (마이그레이션, 점검 SQL)
docs/             기술·심사 문서, 화면 자료
apk/              제출용 APK
```

## 빌드

- 필요: JDK 17, Android SDK 34
- 차량용 release (서명 없음): `gradlew :app:assembleCarRelease`
- 휴대폰 debug: `gradlew :app:assemblePhoneDebug`
- 선택 설정은 `local.properties`에 넣는다. 이 파일은 저장소에 포함하지 않는다.
  - `supabase.url`, `supabase.anonKey`: 비어 있으면 동기화가 꺼진다.
  - `youtube.apiKey`: phone 빌드 전용. 비어 있으면 쓰지 않는다.

## 권한

| 권한 | 용도 | 빌드 |
|---|---|---|
| `RECORD_AUDIO` | 호출어 등록·감지, 음성 명령 | car, phone |
| 위치 | 주차 위치 기록 | car, phone |
| 알림 접근 | 메시지 읽기·답장 | car, phone |
| 차량 속성 (기어·시동·주차 브레이크) | 주차 감지 | car |
| `INTERNET` | 동기화 (설정했을 때만) | car, phone |
| 다른 앱 위에 표시, 접근성 | 플로팅 버튼, 유튜브 화면 조작 | phone |

## 지원 환경

- 차량용: Android Automotive OS, minSdk 28 / targetSdk 34. Pleos 에뮬레이터에서 개발했고, 실차에서는 검증하지 않았다.
- 휴대폰용: Android 9 이상. 실기기 Galaxy Note20 Ultra에서 시험했다.

## 문서

- [PROJECT_OVERVIEW](docs/PROJECT_OVERVIEW.md)
- [FEATURES](docs/FEATURES.md)
- [ARCHITECTURE](docs/ARCHITECTURE.md)
- [VEHICLE_VALUE](docs/VEHICLE_VALUE.md)
- [ROADMAP](docs/ROADMAP.md)
- [TEST_STATUS](docs/TEST_STATUS.md)
- [화면 자료](docs/screenshots/README.md)
- 세부 설계: [VOICE_ASSISTANT](docs/VOICE_ASSISTANT.md), [DRIVE_TODO](docs/DRIVE_TODO.md), [PARKING_DETAIL](docs/PARKING_DETAIL.md), [DRIVE_SYNC](docs/DRIVE_SYNC.md), [PHONE_TEST](docs/PHONE_TEST.md)
