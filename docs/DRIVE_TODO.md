# Drive To-Do

Last parking location, voice to-dos, to-do candidates from notifications, and the post-parking summary.
Parking zone note, photo and the "주차 위치를 기록하시겠습니까?" prompt (2026-11-15): see [PARKING_DETAIL.md](PARKING_DETAIL.md).
Backup taken before this work (APKs and source snapshot): `F:\MyCarHub-backup\before-drivetodo-20261110`.

## 1. Last parking location

| | Car app (Pleos) | Phone app |
|---|---|---|
| Detection signal | AAOS standard vehicle properties via `CarPropertyManager`: `GEAR_SELECTION` (or `CURRENT_GEAR`), `IGNITION_STATE`, `PARKING_BRAKE_ON`. Permission `android.car.permission.CAR_POWERTRAIN` (normal). | Phones cannot read vehicle signals. Fallback: the car Bluetooth device the user picks disconnects. |
| Save rule | `ParkingDecider` (assistant-core, self-tested). Location is captured when the gear goes to P. It is committed when the ignition turns off/ACC/LOCK while in P. Without an ignition signal: P held 3 min with the parking brake on (5 min if there is no brake signal). If the gear leaves P before commit, the captured location is discarded. **Speed 0 alone never saves.** | Saves the phone's current location when the Bluetooth link drops. Traffic lights do not drop Bluetooth. |
| Location | `LocationManager` (GPS, fused, network). No location within 15 s means nothing is saved. A last-known fix is used only if it is under 2 minutes old. | Same. |
| Address | `Geocoder`, only if present. Coordinates go to the platform geocoding service. | Same. |
| Background | `CarParkingService`: foreground service of type location. Started when the main screen is shown, and on boot (Android may refuse the boot start; the service then stops quietly). | Manifest receiver for `ACL_CONNECTED`/`ACL_DISCONNECTED`. On disconnect it starts `PhoneParkingService`, a short location foreground service that runs for up to 30 s and then stops. Android 12+ allows the start right after a Bluetooth broadcast. If the system refuses, the receiver tries for 8 s with `goAsync`. Needs location "Allow all the time"; without it nothing is saved and the summary says so. |
| Accuracy | `ParkingRecorder.currentLocation` asks GPS, fused and network together. It keeps the most accurate fix within the timeout and stops early at 25 m or better. | Same. A spot worse than 100 m is flagged on the card and in the notification. |
| Failure | Nothing is saved; the previous spot stays. | The failure time is recorded and the card says the shown spot is the previous one. The summary notification gets a **지금 위치로 저장** button. |
| Bluetooth flap | — | If the car reconnects within 3 min of an auto save, that save is reverted to the previous spot. |
| Known limits | Not tested on an emulator or a real Pleos car. Pleos may not expose the gear or ignition properties to third-party apps; the service logs which signals it got. | The location is the phone's, so there is error if the phone has moved away. Underground parking may give no fix. Not tested with a real car Bluetooth disconnect. |

Voice: "차 어디 세웠지?" gives the last spot, and the phone also offers walking directions. "여기 주차했어" saves the current location manually.

Phone screen: Drive To-Do › **마지막 주차 위치 확인하기** is always shown.
- It has direct **지도**, **길찾기** and **여기 저장** buttons.
- 지도 opens a `geo:` map app. If none is installed, it opens the Google Maps web page.
- Tapping the card also offers delete.

Debug build only: `adb shell am broadcast -n com.mycarhub.phone/com.mycarhub.phone.debug.DebugCommandReceiver --es car disconnect` (or `connect`) runs the same flow as the Bluetooth receiver.

## 1b. Left in the car (차량에 남긴 물건)

These are records of what the user **said**. No car sensor checks whether the item is really there, and every answer and the screen say so.

- **Parsing:** `CarItems.parse` in assistant-core is self-tested. It runs after the parking checks and before todo parsing, so "차에서 노트북 챙겨야 해" does not become a plain todo.
  - It registers only when the sentence names the car or a spot in it: 차, 트렁크, 뒷좌석, 조수석, 글로브박스, 콘솔.
  - Store only: "차에 택배 상자 두고 내렸어", "트렁크에 운동화 놔뒀어".
  - Pickup: "차에서 노트북 챙겨야 해", or a store sentence followed by "잊지 않게 알려줘" or "내릴 때 알려줘". This creates a linked todo, "차에서 노트북 가져오기".
  - Queries: "차에 뭐 뒀지?", "내 우산 어디 있어?", "차에서 챙길 거 있어?", and "노트북 가져왔어".
- **Record:** `CarItemStore` (prefs `drive_car_items`) stores the name, spot, time, vehicle (phone: the chosen car Bluetooth name), source, pickup and remind flags, retrieved time, and the linked parking id plus a copy of that spot.
  - A new parking spot never removes or rewrites an item that is already linked.
  - **Linking:** while driving (phone: car Bluetooth connected; car: last gear signal not P), the item waits and is linked when the next parking is saved. Otherwise it links to the last saved spot.
  - With no spot, nothing is linked, so no coordinates are shown.
  - A Bluetooth-flap revert unlinks items that were linked to that spot.
- **Pickup ↔ todo:** completing either side completes the other. Deleting the todo keeps the item and only clears pickup. Deleting the item also deletes its todo.
- **Reminders:** only pickup items with remind on.
  - Phone: a line in the post-parking summary.
  - Car: a car notification when `ParkingDecider` commits.
  - Store-only items never notify.
- **Phone screen:** under the parking card there are two sections.
  - **차량에 남긴 물건:** 차 찾기 (map or walking directions to the latest spot), add, 가져왔어요, 알림 끄기/켜기 or 챙기기, edit, and delete. Each row shows which parking it is linked to: the latest one, an earlier one, waiting for the next parking, or none.
  - **하차 후 처리할 일:** the pickup todos. They are kept out of the general to-do list.
- **Debug only:**
  - `--ez save true` with `--es text …` actually saves the result.
  - `--es items dump` logs the items without coordinates.
  - `--es items delete:<name>` and `--es items revert_parking` clean up after tests.

## 2–5. To-dos

- **Voice to-dos:** `DriveCommands.parseTodo` (assistant-core, self-tested) extracts the title, due date and place.
  - "~해야 해/돼", "할 일에 추가해", "나중에 알려줘", "기억해 줘" trigger it.
  - "할 일 뭐 있어?" reads the open to-dos.
- **Notification candidates (phone only):** `TodoSuggester` covers delivery done, schedule change, and a 1:1 question or request.
  - Off by default; a consent dialog appears when it is turned on.
  - Only Kakao and SMS packages are checked.
  - Only the title, source app and notification key are stored, never the message body.
  - Turning it off deletes pending candidates.
- **To-do list (phone):** done, edit, delete, due date, source label, and "열기" (the original notification if it is still active, otherwise the app).
- **Post-parking summary (phone):** "운전 중 놓친 일 N개" counts open to-dos and candidates created since the car Bluetooth connected.
- **Places:** home, work and custom places are set from the current location.
  - A place to-do is announced in the summary when the parking spot is within 300 m of that place.
  - There is no geofencing and no detection of arriving on foot. Google Play Services is not in the project, and it is not claimed.

## 6. Car ↔ phone sync — Supabase, built 2026-11-11 (not yet verified on devices)

The user picked Supabase on 2026-11-10; Bluetooth is on hold.
- See [DRIVE_SYNC.md](DRIVE_SYNC.md) for the design, the behaviour as built, and what is still unverified.
- **What syncs both ways:** to-dos, parking spots and left-in-car items, including deletes.
- **What does not sync:** notification candidates, saved places and settings.
- **Pairing:**
  - Car: main screen › **휴대폰 동기화**, available only while parked.
  - Phone: Drive To-Do › **차량과 동기화**.
  - The car shows a 6-digit code. Both screens then show a verify number, and the user confirms on the car.

The original options table is kept below for reference.

| Option | Server/account | Automatic | Notes |
|---|---|---|---|
| A. QR code on the car screen (parked only) → phone camera → `mycarhub://` deep link | None | No (one scan) | Works offline and can be tested with emulator + phone. Needs a QR encoder in the car app (write one, or approve downloading ZXing core, about 0.5 MB). Payload is small (spot + open to-dos). |
| B. Direct Bluetooth RFCOMM between car app and phone app | None | Yes, while connected | Whether a third-party app may use RFCOMM on Pleos/AAOS is unknown. The emulator cannot pair with a real phone. |
| C. Pleos Connect cloud / account | Pleos account and SDK approval | Yes | Requires downloading the Pleos SDK (approval needed) and checking whether a user-data API exists. |
| D. Own server | Yes | Yes | Excluded: no external server without approval. |

Recommendation: A first (works without a server and can be verified), then B if Pleos allows it.

## Change list

### 2026-11-11: car ↔ phone sync (Supabase)

Backup before this work (source and APKs): `F:\MyCarHub-backup\before-realsync-20261111`.

**assistant-core (pure Kotlin)**
- New: `MiniJson.kt`, `SupabaseApi.kt` (HTTP, auth, token refresh), `SyncEngine.kt` (push/pull, cursor, pairing steps).
- New self-tests: `SyncEngineSelfTest.kt`. Totals: 333/333.
- New live test: `SyncLive.kt` and the `syncLive` task.

**Shared (changes the car APK too)**
- Manifest: `INTERNET`, `ACCESS_NETWORK_STATE`, `DriveSyncActivity`, `DriveSyncJobService`.
- `build.gradle.kts`: `BuildConfig.SUPABASE_URL` and `SUPABASE_KEY` from `local.properties`.
- `DriveSyncState.kt` rewritten (session, data key, status, `SyncSecrets`).
- New `DriveSyncManager.kt` and `DriveSyncActivity.kt`; new `strings_sync.xml`.
- `DriveData.kt`:
  - `ParkingSpot.updatedAt`;
  - `syncIds()` on `ParkingStore` and `TodoStore`;
  - remote apply of a newer version of the same parking.
- `CarItemData.kt`: `syncIds()`.
- `MyCarHubApplication`: starts sync.
- Backup rules exclude `drive_sync.xml`.

**Car only:** `activity_main.xml` (main layout used by the car) gets a **휴대폰 동기화** button. `CarDriveSetup` wires it and blocks it while driving. `Platform.syncRole`.

**Phone only:**
- The Drive To-Do sync note is replaced by a status line and a **차량과 동기화** button.
- The clear-all dialog warns that deletes reach the car.
- `Platform.syncRole`.

### 2026-11-10: phone parking hardening and sync preparation

Backup before this work: `F:\MyCarHub-backup\before-sync-20261110`.

**Shared (changes the car APK too)**
- `ParkingRecorder`: picks the best-accuracy fix instead of the first one, and takes an optional timeout.
- `DriveData.kt`:
  - `ParkingSpot.id`;
  - `ParkingStore.revert`, previous spot, tombstones, `applyRemote`;
  - `TodoStore` tombstones, `origin`, `metaOf`, `applyRemote` and payload.
- New `DriveSyncState.kt` (inactive until pairing).
- `DriveAssistant.webMapIntent`.
- `assistant-core`: new `DriveSync.kt` and `SyncCrypto.kt`, plus 36 new self-checks.

**Phone only**
- `PhoneDrive.kt`: `PhoneParking`, `PhoneParkingService`, failure tracking and notification actions.
- `DriveTodoActivity`: card buttons, warning, save-here intent.
- Layout and strings.
- Manifest: `FOREGROUND_SERVICE_LOCATION`, the service, and a `geo:` `<queries>` entry.

**Phone debug only:** `--es car connect|disconnect` in `DebugCommandReceiver`.

**Server (not applied):** `supabase/migrations/20261110000000_drive_sync.sql`.

### Initial Drive To-Do

**Car only** (`app/src/car`, new)
- `AndroidManifest.xml`: CAR_POWERTRAIN, location, FGS location, boot receiver.
- `car/CarParkingService.kt`, `car/CarDriveSetup.kt`, `res/values/strings_car_drive.xml`.
- `platform/Platform.kt`: adds `voiceTodoSource` and `onAppCreate`.

**Shared — changes the car APK** (`app/src/main`, `assistant-core`)
- `drive/DriveData.kt`, `drive/ParkingRecorder.kt`, `drive/DriveAssistant.kt`, `res/values/strings_drive.xml`.
- `MyCarHubApplication.kt`: calls `Platform.onAppCreate`.
- `assistant/VoiceAssistantSession.kt`: Drive commands are checked first, plus the `Answer` state.
- `ui/AssistantActivity.kt`: renders `Answer`.
- `assistant-core`: `DriveCommands.kt`, `ParkingDecider.kt`, self-tests.

**Phone only** (`app/src/phone`)
- `drive/DriveTodoActivity.kt`, `drive/PhoneDrive.kt`, `drive/TodoCandidateCollector.kt`, layouts, `strings_drive_phone.xml`.
- `PermissionRequestActivity.kt`: location, background location, Bluetooth and notification kinds.
- `NotificationMessageAccess.kt` (`openNotification`, `messageOf`) and `MessageListenerService.kt` (candidates).
- `conversation/PhoneConversation.kt`: drive commands and the directions offer.
- `PhoneMainScreen.kt`, main layout and manifest.
