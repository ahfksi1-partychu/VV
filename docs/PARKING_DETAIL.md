# Parking detail record (주차 위치 상세 기록)

Built 2026-11-15. Backup before this work (source and APKs): `F:\MyCarHub-backup\before-parkingdetail-20261115`.
Version: `versionCode 2`, `versionName 0.2.0` (same package names and signing → installs as an update, data kept).

## 1. What it does

| Step | Car app (Pleos/AAOS) | Phone app |
|---|---|---|
| Parking detection | Unchanged: `CarParkingService` + `ParkingDecider` (gear P, then ignition OFF/ACC/LOCK; or P held with brake). One commit per P cycle. | Unchanged: chosen car Bluetooth disconnects (`CarBluetoothReceiver` → `PhoneParking`). |
| No GPS (underground) | **New:** a record with time only (`latitude/longitude = null`) is saved instead of nothing. | **New:** same. Also when location permission is missing. |
| "주차 위치를 기록하시겠습니까?" | Never asked on the car screen (no text entry in the car). | Asked once per parking event. Main screen in front → in-app dialog. Otherwise → the post-parking summary notification becomes the prompt (high importance, lock screen shows no details). |
| Choices | — | 직접 입력 / 사진 촬영 / 나중에 입력 (dialog) / 건너뛰기. Notification: 직접 입력, 사진 촬영, 건너뛰기; tapping opens the record screen; swiping away = 나중에. |
| Car → phone | The car's record syncs through the existing encrypted Supabase sync. | When a car-detected record arrives and becomes the current record, the phone asks (if this parking was not asked about yet). |
| Main screen | Not changed. Voice answer "차 어디 세웠지?" now includes the zone note. | **New card "최근 주차"** at the top: zone note (or address), parked time and how it was detected, photo, sync status, 지도에서 보기 (only with GPS), 위치 수정, 사진 변경/추가. Unanswered prompt shows inline in the card. |

### Car detection rule change (`ParkingDecider`)

Found on the Pleos emulator: a third-party app can read gear and parking brake, but **not `IGNITION_STATE`**
(log: `gear=true ignition=false brake=true status=GEAR_ONLY`), and the brake was reported off.
With the old rule (brake signal present but off → never commit) no parking was ever saved there. New rule:

- ignition OFF/ACC/LOCK while in P → commit (unchanged);
- P held 3 min with brake on (unchanged), **or 5 min with brake off** (was: never);
- ignition reported ON/START (idling in P) → no time-based commit; wait for ignition off (new).

## 2. Record fields (`ParkingSpot`)

Existing: id, latitude/longitude (now nullable), accuracy, savedAt, updatedAt, address, source.
New: `note` (zone/number, max 40 chars, cleaned by `ParkingNote.normalize`), `photo` (file name), `photoPreview`,
`detailAt` (when note/photo last changed), `fromOtherDevice` (local only).
Old stored records decode unchanged (they always have coordinates).

## 3. Same parking from two devices

`ParkingMerge` (unchanged) picks the car-signal record when a car and a phone record are within 10 minutes.
**New `ParkingCombine`:** the record that is kept takes from the other one
- the location, if the kept one has none (car underground, phone got a fix);
- note and photo, if the other one changed them later (`detailAt`).

The merged record gets a newer `updatedAt` and is pushed again, so both devices converge.
Local auto-saves use the same rule (`ParkingStore.saveDetected`).

## 4. No repeated prompt

`ParkingPromptRules.shouldPrompt` (assistant-core, self-tested): only automatic sources; not when a note/photo exists;
not for records older than 6 h; not for an id already asked, nor for another record of the same parking (10 min window).
The last 20 asked parkings are kept in prefs `parking_prompt`. A Bluetooth flap revert withdraws the notification.
The prompt and the post-parking summary are one notification (id 72), so one parking gives at most one notification.

## 5. Photos and security

- Taken with the system camera app (`ACTION_IMAGE_CAPTURE`) into a non-exported provider (`ParkingCaptureProvider`)
  that exposes exactly one temp file, with a one-time write grant. Picking uses the system photo picker (Android 13+)
  or the document picker. **No `CAMERA`, `READ_MEDIA_IMAGES` or storage permission is declared.**
- Stored in app-private `files/parking_photos/`, re-encoded (max 1600 px, JPEG 85). Re-encoding drops EXIF (GPS, device).
- Excluded from cloud backup and device transfer.
- Only photos of the current and previous record are kept; others are deleted (files younger than 1 h are kept so a
  photo taken but not yet saved is not lost).
- Sync sends a **small preview only** (≤ 7,000 bytes JPEG), inside the existing AES-GCM encrypted record. The server row
  limit is 16,384 chars, so the full photo cannot be sent without a server change (Supabase Storage + RLS), which was not done.
- A received preview is saved under a name derived from SHA-256 of the record id; names or paths from received data are never used.
  Previews are checked (size, JPEG markers, decodable).

## 6. Sync status shown per record

Off (no server config) / not paired (this phone only) / waiting to send / failing (with reason) / received from car /
uploaded to the sync server with last check time. The app cannot know whether the car has pulled the record and does not say so.

## 7. Tests

- `gradlew --offline :assistant-core:selfTest` → 375/375 (42 new checks in `ParkingDetailSelfTest.kt`: note cleaning,
  same-parking detection, combine plan, prompt de-duplication, preview/file-name checks, worst-case record size under 16,384).
- Debug only (`phoneDebug`):
  `adb shell am broadcast -n com.mycarhub.phone/com.mycarhub.phone.debug.DebugCommandReceiver --es parking dump`
  (`car_remote`, `car_remote:nogps`, `note:B구역 13번`). `car_remote` runs a fake car-detected record through the real
  sync payload → apply → prompt path (fixed test coordinates). Clean up with `--es items revert_parking`.

## 8. Emulator verification (2026-11-15)

Both emulators were run with `-read-only` (AVD files unchanged) and temp files on F:.

Pixel_6 (Android 14), phone debug APK:
- Upgrade 0.1.0 → 0.2.0 over a record saved by the old APK: the record was kept and shown on the new card.
- Bluetooth-disconnect flow with the main screen in front → in-app dialog (`40-parking-prompt-dialog.png`).
- 직접 입력 → typing; 사진 촬영 → system camera app, photo returned and saved privately (24 KB + 3 KB preview),
  the note survived the camera round trip (`41-parking-record-photo.png`). The main card shows note, address, time and photo (`42-main-parking-card.png`).
- A car record for the same parking arriving through the sync apply path: the car record won, the phone's GPS,
  note and photo were merged in, **no second prompt or notification**.
- A new GPS-less car record with the app in the background → notification with three actions
  (`43-parking-prompt-notification.png`); tapping it opened the record screen with no map button; text-only save
  → card without map or photo (`44-main-card-no-gps.png`).
- "여기 저장" filled the location into the GPS-less record and kept the note.
- Voice "차 어디 세웠지" answered "B3 A-7" and did not offer directions for the GPS-less record.

Pleos_Connect (AAOS user build), car debug APK:
- `CarParkingService` ran as a location foreground service; signals as above; with the new rule one commit after
  about 5.5 min in P and one CAR_SIGNAL record with GPS saved.
- `cmd car_service inject-vhal-event` is refused on this user build, so gear changes and ignition off could not be simulated.

Not run: real Supabase sync between two devices, photo picker ("앨범에서 선택"), lock-screen display.

## 9. Not verified / limits

- Not run in a real car. Whether Pleos exposes gear/ignition to third-party apps is still unknown (`CarParkingService` logs it).
- Car → phone delivery depends on the car having internet after ignition off; otherwise it is sent on the next drive.
  Phone background pull runs about every 15 minutes (JobScheduler), so a car-only event can reach the phone late.
- Real car Bluetooth disconnect not tested; Supabase live sync still not run (see DRIVE_SYNC.md).
- Location from a merged record may come from the phone even though the source shows "차량에서 감지".
