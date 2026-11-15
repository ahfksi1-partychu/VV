# MyCarHub 음성비서

## 흐름

```
메인 화면(열려 있을 때만)
  └ 호출어 감지 (WakeWordManager, MFCC+DTW)  ─┐
  └ '음성 명령' 버튼 ─────────────────────────┤
                                               ▼
AssistantActivity (주행 중 허용, 글자 입력 없음)
  VoiceAssistantSession
    1. 마이크 권한 확인
    2. 음성 인식기 선택 (기기 내 > 시스템[사용자 동의 시])
    3. 호출어 감지 스레드가 마이크를 놓을 때까지 대기 (WakeWordManager.whenMicReleased)
    4. SpeechToText: 듣기 → 부분 결과 → 최종 후보(최대 5개)
    5. CommandParser: "카카오톡 열어줘" → OpenApp("카카오톡")
    6. AppCommandResolver: 내 앱(별칭·이름) → 설치 앱 → 줄임말(카톡) → 종류(내비게이션=APP_MAPS)
    7. AppLauncher: 설치·활성·주행 제한 확인 후 실행 (즐겨찾기 실행과 같은 경로)
  상태: 준비 → 듣기 → 처리 → 완료 / 선택 / 실패 / 취소
```

| 파일 | 역할 |
|---|---|
| `assistant-core/.../CommandParser.kt` | 한국어 명령 해석 (규칙 기반, 기기 내) |
| `assistant-core/.../AppMatcher.kt` | 앱 이름·별칭·줄임말·종류 매칭 |
| `assistant-core/src/selfTest/...` | PC 자체 검사 `gradlew --offline :assistant-core:selfTest` |
| `app/.../assistant/stt/SpeechToText.kt` | SpeechRecognizer 래퍼, 인식 방식 설정(SttSettings) |
| `app/.../assistant/AppCommandResolver.kt` | 등록 앱·설치 앱·카테고리 조회 |
| `app/.../assistant/VoiceAssistantSession.kt` | 상태 머신 (화면과 분리) |
| `app/.../ui/AssistantActivity.kt` | 음성비서 화면 |

## 정책

- **음성 외부 전송:** 기본은 기기 내 인식기(`createOnDeviceSpeechRecognizer`, API 31+)만 사용한다. 기기 내 인식기가 없으면 '실패' 화면에서 시스템 인식기 사용 동의를 받는다. 이 인식기는 서버로 음성을 보낼 수 있으며, 오프라인 처리를 요청은 하지만 보장되지는 않는다. 동의는 메인 화면 '시스템 음성 인식 허용됨'을 눌러 해제할 수 있다.
- **언어 팩:** 한국어 언어 팩 다운로드(`triggerModelDownload`)는 호출하지 않는다.
- **동작 범위:** 앱 실행만 한다. 메시지 보내기, 전화 걸기, 다른 앱 화면 조작은 '지원하지 않음'으로 답한다.
- **주행 중:**
  - 음성비서 화면, 다시 말하기, 후보 선택은 허용한다.
  - 마이크 권한 요청, 앱 설정 이동, 시스템 인식기 동의는 차단하고 "정차 후 이용"으로 안내한다.
  - 실행 대상 앱이 주행 중 사용 불가 앱이면 실행하지 않고 안내한다.
- **화면 자동 닫힘:** 완료 1.5초, 실패·선택 15초. 다른 앱이 앞으로 오면 인식을 취소하고 닫는다.
- **패키지 가시성:** `QUERY_ALL_PACKAGES`는 쓰지 않고 `<queries>`에 `android.speech.RecognitionService`만 추가했다.

## 검증 상태

**PC에서 검증함**
- 명령 해석과 앱 매칭 자체 검사 48/48 통과.
- debug·release 빌드 성공.

**미검증 (실제 마이크·에뮬레이터 테스트 때 확인)**
- [ ] Pleos 에뮬레이터에 기기 내 인식기가 있는지, 없다면 시스템 인식기가 무엇인지 (Gleo 연동 여부)
- [ ] 한국어 인식 품질: 실제 결과 문장 형태(띄어쓰기, 맞춤법, "카톡" 인식 여부)
- [ ] 호출어 감지 → 음성비서 화면 전환 → 마이크 넘겨받기 → 시작 직후 ERROR_AUDIO 없이 듣는지
- [ ] Gleo가 마이크를 쓰는 중일 때 '다른 앱이 마이크 사용 중' 안내가 나오는지
- [ ] 부분 결과, 입력 레벨 막대, '다 말했어요' 버튼
- [ ] 인식 서비스가 응답하지 않을 때 감시 타이머(5초/15초)가 동작하는지
- [ ] "카카오톡 열어줘": 에뮬레이터에 카카오톡이 없으므로 '찾지 못함' 안내 확인. 실제 실행은 설치된 앱(갤러리 등)으로 확인
- [ ] "내비게이션 실행해 줘": Pleos 내비 앱이 APP_MAPS를 선언했는지, 아니면 이름·패키지 단서로 찾는지
- [ ] 후보가 여러 개일 때 선택 버튼
- [ ] 마이크 권한 없음 → 허용 → 바로 듣기 / 영구 거절 → 앱 설정 안내
- [ ] 주행 중 제한: VHAL 주입이 막혀 있어 에뮬레이터에서는 주행 상태를 만들 수 없음. 실제 차량이나 별도 방법이 필요
- [ ] 음성비서에서 돌아왔을 때 호출어 듣기가 다시 시작되는지
- [ ] 즐겨찾기 등록·실행, 호출어 등록·삭제 회귀 확인

## 메시지 읽기·답장 (2026-11-07 추가)

### 플랫폼별 지원 범위

| 기능 | phone (스마트폰 테스트용) | car (Pleos 차량용) |
|---|---|---|
| 메시지 알림 읽기 | 지원. NotificationListenerService를 쓰며 사용자가 알림 접근을 허용해야 함 | **미지원**. AAOS에서는 메시지 알림을 차량 기본 음성비서(시스템 권한)가 읽고 답장함. 일반 앱용 공식 인터페이스가 없고 Pleos Connect SDK에도 메시지 API가 없음 |
| RemoteInput 답장 | 지원 (답장 동작이 있는 알림만, 확인 후) | 미지원 |
| TTS 읽기 | 오프라인 한국어 음성만 사용하고, 없으면 화면으로만 표시 | 코드는 같지만 메시지 기능이 없어 사용하지 않음 |
| 알림 리스너 서비스 선언 | `src/phone/AndroidManifest.xml` | 선언 없음 (APK에 포함되지 않음) |

플랫폼 차이는 `src/car`·`src/phone`의 `com.mycarhub.platform.Platform`이 결정한다 (`MessageAccess` 구현 선택).

### 흐름

```
"카카오톡에 가장 최근에 온 메시지 읽어줘"
  → CommandParser: ReadMessage("카카오톡")
  → MessageAccess.currentMessages(): 지금 떠 있는 알림만 요청 시점에 조회 (저장 안 함)
  → MessageSelector: 해당 앱의 가장 최근 수신 메시지 (MessagingStyle이면 마지막 '받은' 메시지)
  → Speaker(TTS): "카카오톡, 홍길동님의 메시지입니다. …"
  → 듣기(AFTER_READ): "30분 뒤에 도착한다고 답장해 줘" → Reply("30분 뒤에 도착한다")
  → TTS 확인: "홍길동님에게, 30분 뒤에 도착한다, 라고 보낼까요?"
  → 듣기(CONFIRM): '응/보내' 또는 [보내기] 버튼일 때만 전송
                   '아니/취소' 후보가 하나라도 있으면 보내지 않음, 애매하거나 대답이 없으면 한 번 더 묻고 그래도 아니면 보내지 않음
  → RemoteInput.addResultsToIntent + 답장 PendingIntent.send
```

### 개인정보

- **저장하지 않음:** 메시지 알림을 수신 시점에 받아 두지 않는다. `MessageListenerService`는 연결점일 뿐이다. 읽은 메시지와 답장 초안은 `VoiceAssistantSession` 메모리에만 있고, 화면을 닫으면(`release`) 지운다. 앱이 종료·재시작돼도 남는 기록이 없다.
- **외부로 보내지 않음:** 알림 내용은 기기 안에서만 처리한다. TTS는 `isNetworkConnectionRequired=false`인 한국어 음성만 쓴다.
  - 답장 음성 인식은 기존 STT 정책을 따른다. 기기 내 인식기가 기본이고, 시스템 인식기는 동의한 경우에만 쓴다.
- **로그:** 보낸 사람·내용·인식 문장은 남기지 않고, 앱 패키지·개수·명령 종류만 남긴다.
- **최근 앱 미리보기 제외:** API 33+는 `setRecentsScreenshotEnabled(false)`, 그 미만은 `FLAG_SECURE`.
- **알림 접근 허용 전 안내:** 무엇을 읽는지, 저장하지 않는다는 점, 외부 전송이 없다는 점, 해제 방법을 안내한다. 해제는 시스템 설정에서 한다.

### 미검증 (실제 폰에서 확인 필요)

- [ ] 알림 접근 허용 화면 이동: 갤럭시 Android 13. adb 설치 앱이 '제한된 설정'으로 막히는지
- [ ] 카카오톡 알림 구조: MessagingStyle 사용 여부, 보낸 사람·내용 추출, 단체방 이름
- [ ] 카카오톡 알림의 답장 동작(RemoteInput) 유무와 실제 전송 성공 여부
- [ ] 삼성 메시지(SMS) 알림 읽기·답장
- [ ] 폰에 오프라인 한국어 TTS 음성이 있는지 (삼성 TTS·Google TTS)
- [ ] TTS가 끝난 뒤 바로 STT를 시작할 때 TTS 소리가 인식되지 않는지 (현재 0.3초 쉼)
- [ ] 확인 질문에 대답이 없을 때 다시 묻고, 그래도 없으면 보내지 않는지
- [ ] 알림이 사라진 뒤(앱에서 읽음) 답장 → '알림이 사라짐' 안내
- [ ] 최근 앱 화면에 메시지 내용이 보이지 않는지

## 백그라운드 호출 (플로팅, phone 빌드만, 2026-11-07 추가)

**구성:** 모두 `app/src/phone/java/com/mycarhub/phone/floating/`에 있다. main(공용) 코드와 차량용 APK는 바뀌지 않았다.

| 파일 | 역할 |
|---|---|
| `FloatingVoiceService` | 마이크 포그라운드 서비스, 감지↔음성비서 전환, 지속 알림 |
| `BackgroundWakeWordListener` | 기존 `StreamingWakeWordDetector` + `MicrophonePcmSource`. 감지하면 스스로 마이크를 닫고 끝난다 |
| `OverlayAssistantPanel` | 다른 앱 위 음성비서 창. 기존 `VoiceAssistantSession`을 그대로 사용 |
| `FloatingBubble` | 플로팅 버튼 (누르기·길게 누르기·끌기) |
| `DeviceConditions` | 화면 꺼짐·잠금, 통화(오디오 모드, READ_PHONE_STATE 불필요) |
| `AppForegroundTracker` | MyCarHub 화면이 열려 있으면 백그라운드 감지 쉼 |
| `FloatingVoiceSetupActivity` | 권한 체크리스트와 켜기·끄기 |

**마이크 동시 점유 방지:** 감지 스레드는 감지 즉시 마이크를 닫고 종료한다. 서비스는 스레드 종료(join)를 확인한 뒤 음성비서 창(STT)을 연다. 창이 닫히면 0.5초 뒤 감지를 재개한다. MyCarHub 화면이 생성되는 시점에도 감지를 멈춘다.

**Android 정책 대응**

- **마이크 포그라운드 서비스:** 앱 화면에서만 시작하며, Android 11+ while-in-use 조건을 따른다. Android 14+에서 필수인 `foregroundServiceType="microphone"`과 `FOREGROUND_SERVICE_MICROPHONE`을 선언했다. 백그라운드에서 다시 시작하지 않는다 (`START_NOT_STICKY`).
- **알림:** Android 13+에서는 `POST_NOTIFICATIONS`를 받아야 [듣기 중지] 알림이 보인다.
- **다른 앱 실행:** 백그라운드에서 다른 앱을 실행하는 것은 `SYSTEM_ALERT_WINDOW` 허용 예외로 가능하다. Android 15+에서는 보이는 오버레이 창도 필요한데, 플로팅 버튼·창이 있어 조건을 충족한다.
- **개인정보:** 메시지 내용이 보이는 동안 음성비서 창에 `FLAG_SECURE`를 적용한다. 오디오·인식 문장은 저장하지 않는다.

### 미검증 (실제 기기 확인 필요)

- [ ] 다른 앱(카카오톡·유튜브) 사용 중 호출어 감지 → 음성비서 창 → 명령 처리 → 다시 대기, 전체 흐름
- [ ] 포그라운드 서비스에서 SpeechRecognizer가 정상 동작하는지 (인식 서비스의 백그라운드 마이크 접근)
- [ ] 음성비서 창에서 유튜브·카카오톡 실행 (백그라운드 실행 예외 동작)
- [ ] 음성비서 창에서 메시지 읽기·답장 확인·전송
- [ ] 화면 꺼짐·잠금·잠금 해제 시 쉬기와 재개, 전화 수신·보이스톡 중 쉬기와 재개
- [ ] 다른 앱이 녹음 중일 때 '무음' 표시, 마이크를 열 수 없을 때 재시도
- [ ] 알림 [듣기 중지]·[음성비서], 버튼 길게 누르기로 중지
- [ ] 장시간 실행 시 배터리·발열, 삼성 배터리 최적화로 서비스가 종료되는지
- [ ] 백그라운드에서 호출어 오인식·미인식 빈도 (주변 소음·미디어 재생 중)
