# 아키텍처

## 모듈

```
app (Android, flavor: car | phone)
 ├─ assistant-core   명령 해석(CommandParser), 앱 매칭(AppMatcher), 주차 판단(ParkingDecider),
 │                   동기화(DriveSync, SyncEngine, SyncCrypto, SupabaseApi) — 순수 Kotlin, 자체 시험 포함
 ├─ wakeword-core    MFCC, DTW, 녹음 품질 검사, 호출어 학습(WakeWordTrainer)
 ├─ assistant-phone  (phone) 사람 이름 매칭, 대상 분류
 └─ wakeword-phone   (phone) 소음 적응형 호출어 감지기
```

- `app/src/main`: car와 phone 공통 코드 (호출어 관리, 음성 인식, 앱 실행, 주차, Drive To-Do, 동기화)
- `app/src/car`: 차량용 매니페스트 (`android.hardware.type.automotive` 필수, 차량 권한)
- `app/src/phone`: 휴대폰 전용 화면·서비스 (플로팅 버튼, 대화 엔진, 화면 조작, 미디어·지도 연동)

## 음성 처리 흐름

```
마이크 (VOICE_RECOGNITION)
  → 호출어 감지: MFCC+delta 특징 → 등록 템플릿 3개와 DTW 거리 비교 → 가장 가까운 하나가 기준값 이하이면 호출
  → 음성 명령 인식: Android SpeechRecognizer (기기 내 인식을 우선 사용. 시스템 인식기는 사용자 동의 시에만)
  → CommandParser (규칙 기반 한국어 해석, 기기 내)
  → 실행: 앱 실행 / 메시지 읽기·답장 / 주차·할 일 응답
  → TTS 응답 (필요한 경우만)
```

- 호출어 모델과 녹음은 앱 내부 저장소에만 둔다. 백업에서 제외한다.
- 호출어 기준값은 녹음끼리 비교(leave-one-out)해 사용자마다 계산한다.

## 차량 연동 (AAOS 표준 API)

| 항목 | API |
|---|---|
| 주차 감지 | `CarPropertyManager`: `GEAR_SELECTION`/`CURRENT_GEAR`, `IGNITION_STATE`, `PARKING_BRAKE_ON` |
| 주행 중 화면 제한 | `CarUxRestrictionsManager`, `distractionOptimized` 액티비티 |
| 위치 | `LocationManager` (GPS·fused·network), `Geocoder` |

Pleos 전용 SDK는 **아직 쓰지 않는다** ([ROADMAP.md](ROADMAP.md)).

## 외부 앱 연동

- 앱 실행: `PackageManager` LAUNCHER 조회 + `<queries>` 선언 (`QUERY_ALL_PACKAGES`는 쓰지 않음)
- 메시지: `NotificationListenerService`로 읽기, 알림의 RemoteInput으로 답장
- (phone) 유튜브: 검색 링크로 열고, 접근성 서비스로 첫 영상을 누른다. 재생 여부는 `MediaSessionManager`로 확인한다
- (phone) 지도: `nmap://navigation`, `google.navigation:`, `geo:` 링크
- (phone) 미디어 제어: `MediaController.TransportControls`

## 스마트폰 연동 — Supabase 동기화

- 주차 기록과 Drive To-Do를 차량과 휴대폰 사이에 동기화한다.
- 인증: 기기별 익명 로그인. 테이블은 RLS로 보호하고, 짝 맺기는 RPC로 처리한다 (`supabase/migrations`).
- 기록 내용은 짝 맺기 코드에서 만든 키로 **종단 암호화(AES-GCM)** 한다. 서버는 내용을 볼 수 없다.
- 키는 Android Keystore에 둔다. 앱에는 공개(anon) 키만 넣고, service_role 키는 쓰지 않는다.
- 상세 설계: [DRIVE_SYNC.md](DRIVE_SYNC.md)
