# Drive To-Do sync (car ↔ phone) — Supabase design

Status (2026-11-11):
- **Done:**
  - Server schema applied by the user and `drive_sync_check.sql` reported success (2026-11-11).
  - Transport, engine, pairing screens, Keystore storage, background scheduling and the `INTERNET` permission in both APKs.
  - Offline self-tests pass: `gradlew --offline :assistant-core:selfTest`.
- **Not verified yet:**
  - Live server test (`gradlew --offline :assistant-core:syncLive`) has not run: `supabase.url` in `local.properties` is truncated (`https://tkhrtz...supabase.co`).
  - No emulator or device test of pairing or sync.
- Sync is off in a build whose URL is empty or contains `...`. The phone screen then says it is not synced.

Bluetooth direct sync is on hold until Pleos confirms third-party RFCOMM/BLE is allowed (see the earlier research report).

## 1. Pieces

| Piece | Where | State |
|---|---|---|
| Record ids, tombstones, remote apply | `app/src/main/.../drive/DriveData.kt` (`ParkingStore`, `TodoStore`) | Built; used locally today (delete/clear leave tombstones) |
| Sync state (device id, outbox, pull cursor, session, data key) | `drive/DriveSyncState.kt` (+ `SyncSecrets`: Keystore AES-GCM) | Built. Excluded from backups |
| Merge rules, outbox, backoff | `assistant-core/.../DriveSync.kt` | Built, self-tested |
| Pairing key derivation, record encryption | `assistant-core/.../SyncCrypto.kt` | Built, self-tested |
| Server tables, RLS, pairing RPCs | `supabase/migrations/20261110000000_drive_sync.sql` | Applied by the user; check script passed |
| HTTP client (Auth + PostgREST), token refresh | `assistant-core/.../SupabaseApi.kt`, `MiniJson.kt` | Built; self-tested against a fake transport. Live test written (`SyncLive.kt`), not run |
| Push/pull engine, pull cursor, pairing steps | `assistant-core/.../SyncEngine.kt` | Built; self-tested with an in-memory server |
| Scheduling (change, start, network back, 5 min in-process, JobScheduler 15 min and retry) | `drive/DriveSyncManager.kt` | Built; not run on a device |
| Pairing UI | `drive/DriveSyncActivity.kt`. Car: main screen › 휴대폰 동기화. Phone: Drive To-Do › 차량과 동기화 | Built; not run on a device |

### Behaviour as built (2026-11-11)

- **One network thread per device.** Sync runs and pairing calls are serialized, so refresh-token rotation never races.
- **Re-pairing makes a fresh group.**
  - On the car: [연결 시작] while the car is still in a group first calls `leave_sync`. That deletes the old group and its server rows, so no rows encrypted with the old key remain.
  - On the phone: a confirmed phone leaves before claiming a new code.
- **Detecting an unpair from the other side.** Every run first reads `sync_members`. If the device is no longer a confirmed member of its group, it switches to unpaired and shows the reason; local data is kept.
- **Refresh token rejected:** the device unpairs and forgets its session (the next pairing signs in anonymously again).
- **Push:**
  - Push sends the *current* state of each outbox record, not a stored copy.
  - Records the device no longer knows are dropped. Example: an old parking spot already replaced by a newer one.
  - A 400 on a batch is retried one record at a time. Only the refused record is dropped (for example, a device clock more than a day ahead).
- **Pull:** the cursor stops at rows written within 10 s of the server `Date` header. Rows after that point are fetched again next time; applying them again is a no-op.
- **Parking address:** `ParkingSpot.updatedAt` (new; defaults to `savedAt`) changes when the address is filled in later. The address therefore reaches the other device.
- **Clear-all on the phone** creates tombstones, so it also deletes the synced records on the car. The confirm dialog says so while paired.

## 2. Authentication (per device)

- Each device signs in once with **Supabase Anonymous Sign-In**. That gives each device its own user id, a 1-hour access JWT and a rotating refresh token.
  - These are the long-term per-device credentials.
  - The refresh token is stored encrypted with an Android Keystore AES key and never leaves the device.
- The **6-digit code is single-use pairing only**:
  - It expires after 5 minutes and is stored server-side only as a hash.
  - Attempts are limited to 5 per user per 10 minutes and 60 per minute globally. A wrong code does not raise an error, so the attempt row is kept.
- No email, password or personal account. Uninstalling the app (or clearing app data) loses the device identity; the device then pairs again.

## 3. Pairing flow

1. **Car (parked only):**
   - Signs in anonymously and makes an ephemeral EC P-256 key pair.
   - Calls `create_pairing(car_public_key)`. The car becomes a confirmed `car` member of a new group.
   - Shows the code.
2. **Phone:**
   - Signs in anonymously and makes its own key pair.
   - Calls `claim_pairing(code, phone_public_key)`, which returns the car's public key.
   - The phone becomes an **unconfirmed** member and RLS still denies it every record.
3. **Both devices:**
   - Compute `SyncCrypto.derive(...)`: an ECDH shared secret, then HKDF-SHA256 with the code as salt. This gives a 256-bit data key and a 6-digit **verify number**.
   - The data key never goes to the server.
4. **Car:**
   - Polls `pairing_status`, computes the same verify number and shows "확인 번호 123456 — 휴대폰과 같나요?"
   - The user compares it with the phone screen.
   - Same → `confirm_pairing`: the phone is activated and any previous phone is removed.
   - Different → `reject_pairing`.
   - A server or attacker that substituted public keys yields a different verify number (self-test "공개키 바꿔치기면 확인 번호 다름").
5. **Both:** set `paired = true`, then put every todo, tombstone and the current parking spot into the outbox once (initial push).

## 4. Records and encryption

- **Row layout:** one row per record in `sync_records`:
  - Key: `(group_id, kind, record_id)`.
  - Metadata: `updated_at`, `deleted`, `origin`.
  - Content: `nonce` and `ciphertext`.
- **Kinds:**
  - `todo`: payload is the todo JSON without the notification key, which only means something on the device that saw the notification.
  - `parking`: payload is the spot JSON (coordinates, accuracy, time, address, source).
  - `car_item`: a "left in the car" record (`CarItemStore`): name, spot in the car, pickup and remind flags, retrieved time, linked parking id, and a copy of that parking spot. Its pickup todo travels as a normal `todo` record carrying `carItem`. A car-side record ("트렁크에 운동화 놔뒀어") reaches the phone only through this sync, which is **not connected yet**.
  - Notification candidates, saved places and settings are **not** synced.
- **Encryption:** AES-256-GCM with a random 96-bit nonce.
  - The AAD binds `kind|id|updated_at|deleted|origin`. If the server changes any metadata (for example bumps the time to win, or forges a delete), decryption fails and the record is ignored. This is self-tested.
- **What the server can see:** group membership, device roles, record counts and sizes, and timestamps. It cannot see titles, places or coordinates.

## 5. No loss, no duplicates

- **Idempotent upsert:**
  - The same record id is overwritten, never added again.
  - The server trigger and the app's `SyncMerge` apply the same order: a newer `updated_at` wins; at the same time a delete wins; then the larger `origin`.
  - Stale and duplicate writes are dropped silently on the server.
- **Outbox:**
  - Each change is recorded as `kind:id → updatedAt` and kept until the server acknowledges that version.
  - If the record changed again while sending, the entry stays.
  - The outbox lives in prefs, so it survives app restarts and reboots.
- **Retry:** 30 s, doubling, up to 30 min (`SyncBackoff`).
  - Local data is never removed because a send failed.
  - Triggers: app start, network becoming available, after parking and after a voice todo.
- **Pull:**
  - `seq > last_pulled_seq` ordered by `seq`. Each row is decrypted and applied through `applyRemote`, then the cursor advances.
  - The cursor stops before rows younger than 10 s, because sequence values can commit out of order. Re-applying a row is harmless.
- **Deletes:**
  - Tombstones are kept for 30 days on devices.
  - On the server, the deleted row stays until the group is removed, so an old copy on an offline device cannot come back.
- **Parking:**
  - Each parking event is its own record. Each device shows the winner by `ParkingMerge`:
    - The newest wins.
    - When a car-signal spot and a phone-Bluetooth spot fall within 10 min of each other, the car-signal spot wins.
  - A Bluetooth-flap revert and "delete" create tombstones.
- **Unpair (`leave_sync`):**
  - The phone leaves the group. If the car leaves, the group and all its server rows are deleted.
  - Data on each device is untouched.

## 6. Server-side access control

- RLS is enabled on every table.
  - `anon` (not signed in) gets nothing.
  - `sync_pairings` and `sync_pairing_attempts` have no policies and no grants; they are reachable only through the `security definer` functions.
  - `sync_records`: select, insert and update only when `is_sync_member(group_id)`, which means the caller is a **confirmed** member of that group. There is no delete policy.
  - `sync_groups` and `sync_members`: read-only for members.
- A device belongs to at most one group (`unique(user_id)`).
- **Checks to run after applying (not yet done):**
  - Two anonymous users in different groups cannot read each other's rows.
  - An unconfirmed phone gets zero rows.
  - A stale upsert does not overwrite a newer one.

## 7. Supabase project setup (user action; nothing has been created)

1. Create a project at supabase.com (free plan).
   - Choose region **Northeast Asia (Seoul)**.
   - Free projects pause after 7 days without traffic. While paused, sync waits in the outbox and local data stays.
2. Under Authentication › Sign In / Providers, enable **Anonymous sign-ins**. Keep the CAPTCHA option in mind (§8).
3. In the SQL Editor, run `supabase/migrations/20261110000000_drive_sync.sql`.
   - `pgcrypto` is preinstalled in the `extensions` schema on Supabase.
   - The script is one transaction and is safe to run again: it creates tables only if they are missing and redefines functions, policies and grants.
   - Then run `supabase/tests/drive_sync_check.sql`. It creates fake users, checks pairing, RLS, cross-group isolation, `car_item`, the stale-write guard and anon denial, then rolls back. Expect the notice `DRIVE SYNC CHECK: ALL PASSED`.
4. Copy the **Project URL** and the **publishable (anon) key** from Project Settings › API.
   - Never use the `service_role` key in the apps.
5. Give those two values to the build in `local.properties` (not committed). They become `BuildConfig` fields. Sync stays off when they are empty.
6. Then the transport and pairing UI can be built and tested. The `INTERNET` permission is added to both APKs at that point. Pleos App Market review must accept it, and a privacy notice is needed: encrypted location and todo data are stored on Supabase.

## 8. Risks and limits

- **Anonymous sign-up abuse:** anyone holding the publishable key can create anonymous users.
  - Supabase rate-limits anonymous sign-ins per IP (30/hour by default), and CAPTCHA can be enabled.
  - Such users can never read data without a pairing confirmed on the car screen.
- **Pairing-code guessing:** at most 60 attempts a minute globally against a 5-minute code (about 0.03% per offer). A successful guess still fails at the verify-number step.
- **Clock skew:** last-writer-wins uses device clocks. The server rejects times more than 1 day in the future.
- **Pleos:** whether the car app may use the network and run the pairing screen while parked depends on App Market review.
- **Car connectivity:** it is unknown whether the Pleos car has internet when parked (ignition off).
