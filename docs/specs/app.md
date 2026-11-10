# FERA Quests — Client App Spec (Android & iOS)

| | |
|---|---|
| Source | *Anonymous Quest Engine – Technical & Architectural Specification* (PDF) |
| Status | Draft v0.1 — 2026-11-09 |
| Companion specs | [`protocol.md`](protocol.md) (crypto & wire formats — normative) · [`backend.md`](backend.md) (server) |
| Modules | `:shared` (KMP, business logic only) · `:androidApp` (Jetpack Compose) · `iosApp/` (SwiftUI, Xcode) · all depend on `:protocol` |

---

## 1. Goals & non-goals

**Goals**
- Let **participants** discover quests, claim them, submit proof and earn **points** (blind-signed vouchers, 1 voucher = 1 point) with no account, email or phone number, and without the server being able to link their quests together.
- Let **leaders** publish quests, review end-to-end-encrypted proofs on their own device, issue points, and accept points that participants spend with them.
- Share **business logic only** between Android and iOS in one Kotlin Multiplatform module. The UI stays **stack-specific**: Jetpack Compose on Android, SwiftUI on iOS (decided 2026-11-09). Both apps are distributed outside the app stores (Decision D4).

**Non-goals (v1)**
- Desktop or web clients.
- Push notifications. FCM/APNs tokens are a stable device identifier, so status is fetched by polling (§7.4).
- In-app chat between leader and participant.
- A catalogue of what points buy. The app records "leader X accepted *n* points", and what the leader hands over in return happens outside the app.
- Remote (not in-person) spending of points.

---

## 2. Roles

| Role | How the app knows | Capabilities |
|---|---|---|
| Participant | Default for every identity | Browse, claim, submit, track status, hold and spend points |
| Leader | The identity's leader signing pubkey is in the authorized-leader list (`GET /v1/leaders`), backed by an M-of-N admin authorization (protocol §4.1) | All participant features, plus create/pause/close quests, review inbox, issue points, accept points, take over transferred quests, mint invite codes |

The same mnemonic serves both roles: leader keys are derived with different HKDF labels (protocol §3.2). A leader turns on **Leader mode** in Settings. This shows the leader pubkeys as a QR code to send to the admins out of band. M admins then co-sign the authorization with the admin CLI (backend spec §5.7).

---

## 3. Module & package structure

> ℹ️ **Decided 2026-11-09:** the apps share **only business logic**. The UI must remain **stack-specific**: Jetpack Compose on Android, SwiftUI on iOS. This replaces the earlier single Compose Multiplatform `:sharedUI` module. Presentation state (view models / observable objects), navigation, strings, theming and platform services are written per platform, on top of the same shared domain layer.

```
:protocol            (KMP: android, iosArm64, iosSimulatorArm64, jvm)
  org.ferabrasil.quests.protocol
    crypto/          Hkdf, Bip39, KeyDerivation, SealedBox, Rsabssa, Hpke, Ed25519 wrappers
    privacypass/     TokenChallenge, TokenRequest, Token (type 0x0002)
    ohttp/           Bhttp, OhttpClientContext, KeyConfig
    encoding/        hex, base64url, SigInput (protocol §1, §4)
    sign/            RequestSigner (§9)
    model/           DTOs & enums (protocol §11, backend §5)

:shared              (KMP: android, iosArm64, iosSimulatorArm64; iOS framework "Shared",
                      which re-exports :protocol). NO UI code: no Compose, no view models, no strings.
  org.ferabrasil.quests
    di/              Metro graph (AppGraph) + createAppGraph(platform implementations…)
    platform/        interfaces implemented by each app (§4): SecretStore, …
    interop/         Swift helpers: Watchable (StateFlow → callback), Cancellable
    data/
      net/           ApiClient (Ktor + OHTTP plugin), ApiService (typed endpoints)
      vault/         Vault (encrypted kstore), repositories
    domain/          repositories & use cases exposing StateFlow + suspend functions
      identity/      IdentityRepository, WipeIdentity
      preferences/   PreferencesRepository (theme, leader mode)
      quests/        QuestRules (claim eligibility), QuestSearch (on-device search, C6)
      net/           ApiFailure (§7.3 error → UX mapping)
      sync/          SyncTiming (§7.4 jitter/backoff), StatusSync, TokenRefill
      …              ClaimQuest, SubmitProof, CollectVoucher, ReviewSubmission, …

:androidApp          Jetpack Compose + Material 3, androidx Navigation (type-safe routes),
  org.ferabrasil.quests.android    androidx ViewModels over the shared repositories
    QuestsApplication, MainActivity
    platform/        KeystoreSecretStore, ImageSanitizer, CameraCapture, BackgroundScheduler (§4)
    ui/              theme/  privacy/ (ScreenPrivacy)  onboarding/  main/  quests/  claim/
                     submit/  mine/  wallet/  leader/  settings/
    res/values*/     strings (en, pt-BR)

iosApp/              SwiftUI, ObservableObject state holders over the shared repositories
  iosApp/            QuestsApp (entry), AppModel, *View.swift,
                     KeychainSecretStore & other §4 implementations (Swift),
                     KotlinInterop.swift (KotlinByteArray ↔ Data, Watchable subscriptions),
                     Localizable.xcstrings (en, pt-BR)
```

**Swift interop rules** (no extra tooling; plain Kotlin/Native Objective-C export):
- Shared state is a `StateFlow`. Android collects it directly; Swift wraps it in `Watchable<T>` and receives values on the main thread.
- Suspend functions called from Swift are `async`. Every one of them is annotated `@Throws(Exception::class)`, otherwise a Kotlin exception crashes the app instead of becoming a Swift error.
- Platform services are Kotlin **interfaces** in `:shared`, implemented in each app's own language and passed to `createAppGraph(...)`. Their methods are synchronous and `@Throws`, so Swift can implement them as plain `throws` methods.

**Dependencies:** `:shared` uses Ktor client, kotlinx-serialization, kotlinx-coroutines, kotlinx-datetime, Metro (DI), kstore, Napier, multiplatform-settings (non-secret UI preferences only) and `cryptography-random` (CSPRNG). `:androidApp` uses the androidx Compose BOM (ui, foundation, material3, material-icons-core), activity-compose, navigation-compose, lifecycle-viewmodel/runtime-compose. Coil (Android) may be added for **public** images only (see §9). Compose Multiplatform is **not** used.

**To add:** the crypto deps of `:protocol` (protocol §2), CameraX (Android) / AVFoundation (iOS) behind the §4 interfaces, and a QR scanner (ML Kit is **not** allowed because it is a Google Play Services dependency; use `zxing-cpp` on Android and `AVCaptureMetadataOutput` on iOS).

**Forbidden dependencies:** analytics, crash reporters (Firebase, Sentry, etc.), Google Play Services, ad SDKs, any SDK that opens its own network connections. CI must enforce this with a dependency allow-list check.

---

## 4. Platform abstractions

Business-logic-facing services are **interfaces in `:shared`** (`platform/`), implemented in each app's own stack (Kotlin in `:androidApp`, Swift in `iosApp/`) and injected through `createAppGraph(...)`. Purely visual concerns (`ScreenPrivacy`, `CameraCapture` preview) have no shared interface and live entirely in the app.

`SecretStore` contract: `get` returns null **only** when the secret does not exist; any other failure throws, so an unreadable identity is never mistaken for a missing one and overwritten.

| Interface | Android implementation (Kotlin) | iOS implementation (Swift) |
|---|---|---|
| `SecretStore` — get/put/delete a small secret (the mnemonic entropy, 32 bytes) | A random AES-256-GCM key in **Android Keystore** (`setUserAuthenticationRequired(false)`, `setUnlockedDeviceRequired(true)`) wraps the entropy, and the wrapped blob is stored in app-private files. StrongBox is used when available. | Keychain generic password, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, `kSecAttrSynchronizable = false` |
| `ImageSanitizer` — `(uri) -> ByteArray(JPEG)` | `ImageDecoder` decode into a `Bitmap`, apply the EXIF orientation to pixels, downscale to ≤ 2048 px on the long edge, then `Bitmap.compress(JPEG, 85)`. Re-encoding a bare bitmap writes **no** EXIF/XMP/GPS. | `CGImageSource` decode → apply orientation → `CGImageDestination` JPEG with an **empty** properties dict. Never `UIImage.jpegData` (it can carry metadata). |
| `CameraCapture` | CameraX in-app capture (never `ACTION_IMAGE_CAPTURE` to a third-party camera app, which could geotag or sync the photo) | `AVCaptureSession` in-app |
| `ScreenPrivacy` (UI only) | `SecureScreen()` composable: `FLAG_SECURE` on the window while mnemonic, proof preview or wallet is visible | Blur overlay while the scene is not active; `SecureContent` hides sensitive views during screen capture (`UIScreen.isCaptured`) |
| `BiometricGate` (optional app lock) | `BiometricPrompt` | `LAContext` |
| `BackgroundScheduler` | `WorkManager` periodic work (no Play Services) | `BGAppRefreshTask` (best-effort) plus a refresh when the app comes to the foreground |

`ImageSanitizer` has a test requirement: run each output through a metadata parser (e.g. `androidx.exifinterface` / `CGImageSourceCopyPropertiesAtIndex`) and assert that there is **no** GPS, Make, Model, serial number, DateTimeOriginal, XMP or MakerNote. Test fixtures include photos from at least 3 real devices with GPS turned on.

---

## 5. Features & screens

### 5.1 Onboarding

| Screen | Behaviour |
|---|---|
| Welcome | Explains in plain pt-BR/English: no account, your 24 words are your identity, and losing them means losing your vouchers. |
| Create identity | Generates entropy, shows the 24 words in a 3×8 grid under `ScreenPrivacy`. Copy to clipboard is **not offered**. |
| Verify backup | The user taps 4 random words in order. Required before continuing. |
| Restore identity | 24-word entry with wordlist autocomplete and checksum validation. Optional BIP-39 passphrase sits behind "Advanced". |
| Enrollment | Scan or paste an invite code (protocol §6.4). On success the first batch of tokens is fetched. Without enrollment the user can browse but not claim. |

### 5.2 Participant

| Screen | Behaviour |
|---|---|
| Quest feed | The **full** public quest list, fetched page by page (never filtered server-side, so the server cannot see search terms). Search and filter happen on device. Each card shows the title, leader alias (local petname or short key fingerprint), reward units, slots remaining and expiry. The quest signature is verified on device, and invalid quests are dropped and logged locally. |
| Quest detail | Description, verification mode, rules. The **Claim** button is disabled when there are no tokens, no slots or the quest is expired, with the reason shown. |
| Claim | Derives the quest key, spends one token, signs the claim (protocol §4). Shows a progress state and handles `409 already claimed` by restoring the existing claim. |
| Submit proof | Depends on `verification_mode`:<br>• **MANUAL_REVIEW**: capture or pick a photo, and/or enter text → **Preview of the sanitized image** (what the leader will see) → submit.<br>• **SECRET_PREIMAGE**: scan a QR code or type the code → submit.<br>Shows chunked upload progress. Upload can resume if the app is killed. |
| My quests | Local list of the user's claims with statuses (CLAIMED → SUBMITTED → APPROVED/REJECTED/EXPIRED). Built only from the local vault and status sync, with no server-side "my claims" endpoint. **Abandon claim** is available while CLAIMED. **Resubmit required:** if status sync returns `SUPERSEDED` (the quest was transferred to a new leader, backend §6.6), the claim goes back to CLAIMED with a banner "this quest has a new leader — please submit your proof again". The app re-encrypts to the new leader's key and regenerates blinding for the new voucher key. |
| Wallet | Large **points balance** (count of unspent vouchers), with a breakdown by issuing leader and the history of earned and spent points. |
| Spend points | Choose *n* points → the app uploads those vouchers to the redeemer under a random spend code (protocol §7.4) → shows a QR code for the leader to scan, with a 10-minute countdown → on ACCEPTED, the points are marked spent and a receipt ("*n* points accepted by <leader alias>") is shown. On timeout the points return to the balance. |
| Tokens | Settings → Tokens: remaining tokens this epoch and when the next batch is due. |

### 5.3 Leader

| Screen | Behaviour |
|---|---|
| Leader home | Shows authorization status, voucher key status (current epoch and expiry), pending reviews count, and **quests transferred to you**. |
| Take over quest | For each quest in `TRANSFER_PENDING` assigned to this leader: shows the quest and its admin-signed transfer record, then **Accept & re-sign**. The app re-signs the quest with this leader's signing key, encryption key and current voucher key, and the quest returns to its previous status. For SECRET_PREIMAGE quests, the new leader doesn't need the secret, because the server verifies it. |
| Accept points | Scan a participant's spend QR → the app calls the redeemer → shows "*n* points accepted" in large type, or the reason it failed (expired, already spent, invalid). Also shows the leader's history of points accepted per day. |
| Create / edit quest | Fields: title (≤255), description (markdown subset), verification mode, max participants, reward units, expiry. For SECRET_PREIMAGE the app generates the secret and shows or exports a QR code. The quest is signed on device (protocol §4) and published. **Anonymity warning:** if the leader has fewer than 3 active quests in this voucher epoch, show "vouchers from this quest may be attributable to it when redeemed" (protocol §7.4). |
| Manage quest | Pause, resume or close. Shows claim and submission counts. |
| Review inbox | List of PENDING submissions for this leader's quests. Opening one streams the sealed artifact chunk by chunk, decrypts it **in memory** (protocol §5), and renders it. Decrypted bytes are never written to disk or the image cache. |
| Review decision | **Approve** signs each `blinded_msg` with the voucher key and posts the review (protocol §4, §7.3). **Reject** takes an optional reason. The reason is sealed to the participant's quest pubkey (converted to X25519) so that the server can't read it. |
| Auto-approved queue | For SECRET_PREIMAGE quests the server marks submissions APPROVED_PENDING_SIGNATURE. The leader app signs those vouchers automatically in the background whenever it is online. |
| Invites | Mint single-use invite codes (QR or text). Shows how many have been used. |
| Voucher keys | Generate the next epoch key ahead of time, register it, and export an encrypted backup. |

### 5.4 Settings

Language (pt-BR / en), theme (existing), app lock, network status (relay reachability, pinned OHTTP key fingerprint), leader mode, **Export encrypted vault** (for leaders, this includes the RSA voucher keys), **Wipe identity** (requires typing the confirmation and deletes the SecretStore, vault and caches), **version** (`AppInfo.version` from `:shared`, i.e. the platform's entry in `versions.properties` including any pre-release suffix, selectable so it can be copied into a bug report), about and licences.

### 5.5 Navigation map

```
Onboarding ─► (Main tabs)
              ├─ Quests ─► Quest detail ─► Claim ─► Submit proof
              ├─ My quests ─► Claim detail ─► Submit proof
              ├─ Wallet ─► Spend points ─► QR
              └─ Settings ─► Tokens | Leader mode | Export | Wipe
Leader mode on ─► extra tab "Leader" ─► Home | Create quest | Manage | Review inbox ─► Review
                                        | Take over quest | Accept points (scanner) | Invites | Voucher keys
```

---

## 6. Local data (the vault)

Everything stays on the device and is encrypted at rest with the **vault key** (protocol §3.2). The vault is a kstore file whose contents are serialized and then ChaCha20-Poly1305-encrypted with a fresh nonce on every write.

```kotlin
@Serializable data class Vault(
  val schemaVersion: Int,
  val enrollment: Enrollment?,                 // enrolled_at
  val tokens: List<StoredToken>,               // finalized Privacy Pass tokens + issuer key id + epoch
  val claims: List<LocalClaim>,                // questId, claimId, questSnapshot, status, timestamps
  val pendingUploads: List<PendingUpload>,     // uploadId, sealed file path, nextChunk
  val voucherRequests: List<VoucherRequest>,   // claimId, serial, inv (blinding inverse), msg
  val vouchers: List<Voucher>,                 // voucherKeyId, serial, sig, leaderPubkey, state (UNSPENT/LOCKED/SPENT)
  val spends: List<Spend>,                     // spendCode, voucher serials, createdAt, status, acceptedBy
  val adminSetChain: List<AdminSet>,           // latest verified chain from the pinned genesis set
  val leader: LeaderState?,                    // voucher RSA keys (private), quests authored, invites minted
  val petnames: Map<String, String>,           // leaderPubkey -> local alias
)
```

- The sealed artifact for a pending upload is written to app-private cache **only in ciphertext form**, and deleted once the upload completes.
- **Android:** `android:allowBackup="false"` and `android:dataExtractionRules` excluding everything. **iOS:** files are marked `NSURLIsExcludedFromBackupKey` and use `NSFileProtectionComplete`.
- **Restore** with only the mnemonic: the vault is gone, so the app rebuilds claims by deriving quest keys for every quest in the public list and querying blind status (protocol §8), throttled and jittered. Unspent vouchers and blinding factors **cannot** be recovered without a vault export, and the UI states this during onboarding.

---

## 7. Networking

### 7.1 Transport

- One Ktor `HttpClient` (OkHttp engine on Android, Darwin on iOS) whose **only** destination is the OHTTP relay URL from `BuildConfig`.
- An `OhttpPlugin` encodes each logical request as BHTTP, HPKE-encapsulates it with the pinned gateway key config, POSTs `message/ohttp-req` to the relay, and decapsulates the response (protocol §10).
- `ApiService` exposes typed suspend functions over the logical endpoints in backend spec §5. Screens never see raw HTTP.
- **Headers:** no `User-Agent` (set a constant `"aqe"`), no cookies, no `Accept-Language`. The OkHttp cache is disabled. TLS uses the platform defaults.
- **Logging:** Ktor `Logging` is installed in debug builds only, and `Napier` is set to no-op in release builds. A lint rule forbids logging any variable named `*key*|*seed*|*mnemonic*|*token*|*sig*`.

### 7.2 Request signing

`RequestSigner` (in `:protocol`) adds `AQE-Auth` (protocol §9) using the key that the endpoint needs: the quest key for claim/upload/submit, the root key for issuance, the leader key for leader routes.

### 7.3 Error handling

| Condition | UX |
|---|---|
| Relay unreachable / timeout | Offline banner, with automatic retry and exponential backoff (max 5 min) |
| `401` bad signature / stale ts | Resync the device clock offset from the response `Date` header and retry once |
| `402`/`429` no valid token | Prompt "no claim tokens left until <date>" |
| `409` duplicate claim | Treat as success and fetch the existing claim through the status lookup |
| `410` quest closed/expired | Mark it locally and remove it from the feed |
| OHTTP key config mismatch | Fetch the signed config (backend §5.1), verify it against the pinned signing key, and update |

### 7.4 Timing hygiene

- **Status sync:** every 30–90 min (uniform random) while the app is in the foreground, and through `BackgroundScheduler` when it isn't. Each active claim is polled in its own request, with 2–20 s random gaps between them. Polling stops 14 days after a final status.
- **Token refill:** at a random moment in the first 24 h of a new epoch (protocol §6.5).
- **Claim after browse:** there is no artificial delay, since it would hurt UX. But the feed is always fetched in full, so browsing reveals nothing.
- **Spending points** is in person and interactive, so no delay is added. The vouchers' issuance time and spend time are still separated by the review process.

---

## 8. Proof pipeline (participant)

```
capture/pick ─► ImageSanitizer (decode→orient→resize→re-encode; no metadata)
             ─► preview to user ─► Envelope (protocol §5.3, padded to bucket)
             ─► SealedBox.encryptStream(leader_enc_pubkey, quest_id, claim_id)
             ─► write ciphertext to cache file
             ─► POST /v1/uploads → upload_id
             ─► PUT chunk n (≤ 192 KiB of ciphertext per OHTTP request) … resumable
             ─► generate voucher blinding (protocol §7.3), persist to vault FIRST
             ─► POST /v1/claims/{id}/submissions {upload_id, sha256, blinded_msgs, sig}
             ─► delete cache file; claim → SUBMITTED
```

The ordering is deliberate: the blinding inverses are saved to the vault **before** the submission is sent. If they were lost, an approved submission would yield vouchers nobody could unblind.

---

## 9. Security & privacy requirements (checklist)

| # | Requirement |
|---|---|
| C1 | The mnemonic entropy exists only in `SecretStore` and in transient memory. Zero byte arrays after use where the platform allows it. |
| C2 | No network destination other than the relay. CI enforces this with a test that installs a Ktor engine mock and fails on any other host. |
| C3 | No EXIF, GPS, device model, serial number or timestamps in uploaded images (§4 test requirement). |
| C4 | Decrypted proofs (leader side) never touch disk, any image cache (Coil on Android, `URLCache`/`AsyncImage` on iOS) or the clipboard. |
| C5 | Screens showing the mnemonic, proof previews, review content or the wallet use `ScreenPrivacy`. |
| C6 | The server is never told which quests a user browsed. There are no server-side search or filter parameters. |
| C7 | Every quest signature is verified before display. A quest's `leader_pubkey` must be in the leader list, with an M-of-N admin authorization verified against the admin-set chain from the pinned genesis set. Transferred quests are verified per protocol §11.1. |
| C8 | No push notifications, no analytics, no crash reporting. Crashes are written to a local log that the user can export manually. |
| C9 | Release builds are R8/obfuscated on Android and stripped on iOS, with reproducible builds for Android (§10). |
| C10 | All user-visible strings are localized in en and pt-BR, per platform: Android `res/values` + `res/values-pt-rBR`, iOS `Localizable.xcstrings`. `:shared` contains no user-visible strings; it returns enums (e.g. `ClaimBlocker`, `ApiFailure`) that each UI maps to its own text. |

---

## 10. Build, distribution & updates (Decision D4)

| Platform | Channel | Notes |
|---|---|---|
| Android | Direct APK download plus an **F-Droid** repository (self-hosted repo first, main F-Droid later) | `minSdk` **raised to 30** (Android 11), decided 2026-11-09 and applied in the `protocol`, `shared` and `androidApp` build files. This gives ImageDecoder, `setUnlockedDeviceRequired`, more reliable Keystore, and scoped storage everywhere. Signing key kept offline. **Reproducible builds** are required for F-Droid verification: pin the JDK, use deterministic resource ordering, set `SOURCE_DATE_EPOCH`. No Play Services anywhere in the dependency graph. |
| iOS | AltStore / SideStore (`.ipa` + source JSON) and EU alternative marketplaces where available | No App Attest or StoreKit. Bundle ID `org.ferabrasil.quests`. 7-day re-sign limits on free developer accounts must be explained in the install guide. |

**Update check:** the app fetches `GET /v1/config` over OHTTP, which includes `latest_version` and a signed release manifest (hashes per platform). The app shows an update banner with a link. It never self-updates silently.

**CI (GitHub Actions or self-hosted):** `./gradlew :protocol:allTests :shared:allTests :androidApp:assembleRelease`, a macOS job that runs the iOS simulator tests and `xcodebuild` for `iosApp` (which builds the `Shared` framework through `:shared:embedAndSignAppleFrameworkForXcode`), the forbidden-dependency check, and the reproducible-build diff check.

---

## 11. Testing strategy

| Layer | Tooling | Must cover |
|---|---|---|
| Protocol | `kotlin.test` in `:protocol` `commonTest`, all targets | Every RFC vector (protocol §12), sealed-box round trip including truncation and reordering attacks, SigInput golden vectors |
| Domain | `:shared` `commonTest` + `kotlinx-coroutines-test`, fakes for `ApiService` and the §4 interfaces | Claim/submit/voucher state machines, resume after a crash at each step of §8, restore from mnemonic. All business rules are tested here once, not per platform. |
| UI — Android | `androidx.compose.ui.test` (instrumented) + JVM tests for ViewModels | Onboarding backup verification, claim disabled states, review approve/reject |
| UI — iOS | XCTest for `ObservableObject` state holders + XCUITest | The same scenarios as Android |
| Platform | Android instrumented tests and iOS XCTest | `ImageSanitizer` metadata stripping, `SecretStore` persistence across app restarts and its absence after reinstall |
| End-to-end | Backend `docker compose` (backend §10) + Android emulator | Full lifecycle: enroll → token → claim → upload → leader review → points → spend at another leader. Also: leader revoked → quest transferred → participant resubmits. |

---

## 12. Milestones (client)

| ID | Scope | Exit criteria |
|---|---|---|
| A0 | Create `:protocol` module with HKDF, BIP-39, Ed25519, SigInput and DTOs | Vectors pass on JVM/Android/iOS |
| A1 | Onboarding plus `SecretStore` and the vault | Create/restore/wipe works on both platforms |
| A2 | OHTTP client, BHTTP, quest feed with signature verification | Feed loads through a test relay against the dev backend |
| A3 | Privacy Pass issuance, enrollment and claim | Claim succeeds, the token is spent once, and a duplicate claim is handled |
| A4 | ImageSanitizer, sealed box, chunked resumable upload, submission | Leader-side decrypt of the uploaded artifact matches the sanitized input |
| A5 | Leader mode: create quest, review inbox, approve/reject with blind signing | Voucher appears in the participant wallet and verifies |
| A6 | Points wallet, spend QR, leader "accept points" scanner, status sync scheduler, timing jitter | A spend is accepted once, a double spend is rejected, and an expired lock returns the points |
| A6b | Quest takeover (leader) and resubmission (participant) after transfer | Transfer E2E test passes, and a forged transfer (fewer than M signatures) is rejected by the client |
| A7 | Hardening and release: checklist §9, reproducible build, F-Droid repo, AltStore source | Every item in §9 is verified, and an external crypto review is booked |

---

## 13. Decisions log & open questions

**Decided:** recorded as Architecture Decision Records in [`../adr/`](../adr):

- [0002](../adr/0002-privacy-pass-rate-limit-tokens.md) Privacy Pass rate-limit tokens (D1) · [0003](../adr/0003-points-as-chaumian-ecash-vouchers.md) points as e-cash vouchers, redeemer run by leaders (D2) · [0004](../adr/0004-ohttp-through-cloudflare-relay.md) OHTTP via Cloudflare (D3) · [0005](../adr/0005-distribute-outside-app-stores.md) distribution outside the stores (D4)
- [0006](../adr/0006-invite-codes-as-sybil-gate.md) invite codes, invite tree stored · [0007](../adr/0007-m-of-n-admin-co-signing.md) M-of-N admin co-signing · [0008](../adr/0008-leader-revocation-transfers-quests.md) revocation transfers quests, issued points stay spendable
- [0009](../adr/0009-versioned-quest-key-label.md) `"AQE-v1 quest:"` label · [0010](../adr/0010-shared-business-logic-native-ui.md) shared business logic, native UI (§3) · [0011](../adr/0011-android-min-sdk-30.md) `minSdk` 30 · [0012](../adr/0012-independent-versions-per-target.md) per-target versions and reviewed draft releases (§10)

**Open**
1. **Admin set size:** confirm M = 2, N = 3, and who holds the genesis admin keys (needed before A2/B1 so it can be pinned in the build).
2. **Points semantics:** confirm the interpretation of bearer points spent in person at any leader, with no server-side balance (protocol §7). If you need a public leaderboard or a running total per person, that requires a linkable account and conflicts with the core unlinkability tenet.
3. **Can points expire?** Today, points from a voucher key are accepted until 90 days after the key's 30-day epoch ends, so roughly 3–4 months after they are issued.
