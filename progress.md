# Implementation Progress

Last reviewed: October 6, 2026.

## How to Use This File

This is the repository's implementation tracker. Read it before choosing work and update it when implementation or verification changes. [README.md](README.md) explains the architecture; the [project specification](docs/assister-android-project.md) defines intended behavior. The specification's acceptance checklists describe requirements, not current completion.

- Check an implementation item only when its code or artifact exists. Record partial work explicitly and leave the item unchecked.
- Record validation separately, with commands, outcomes, and limitations. Code present does not mean device or server behavior has been verified.
- After a task, update affected items, verification evidence, remaining work, and the review date. Do not mark a milestone complete based only on scaffolding or example tests.
- Before implementing protocol behavior, read the current [canonical protocol](https://github.com/GaryJS3/Assister/blob/main/docs/rich-client-protocol.md). This tracker does not define wire formats.

## Currently Implemented

- [x] Single Kotlin Android `app` module; package/application ID `net.thegary.assister`.
- [x] AndroidX, Jetpack Compose, Material 3, and Compose theme files.
- [x] API 26 minimum; compile/target SDK 37 configuration.
- [x] Launcher `MainActivity` with conversation, progress, response, input controls, and settings UI.
- [x] Application name, launcher icons, manifest, and explicit private-data backup/transfer exclusions.
- [x] Gradle wrapper and TOML dependency version catalog.
- [x] JUnit 4 reducer/transport tests; Compose UI, Keystore, microphone, lifecycle, and mock reconnect/retry tests.
- [x] Contributor guide, architecture overview, project specification, and this tracker.

The MVP implements typed requests and buffered voice input with server progress and streamed answers. It restores one current conversation; a conversation chooser is not yet implemented. Default server: `http://10.44.0.33:8081`. Credentials are entered in Settings, encrypted using Android Keystore, and never embedded in the APK.

## MVP: Live Text and Voice Requests

Deliver one complete flow: configure and authenticate to Assister, open a conversation, send text, observe live execution and streamed response, and recover without creating a second interaction.

### Foundation and Connectivity

- [x] Editable server URL, bearer authentication, and settings UI.
- [ ] Client/device naming and capability registration.
- [x] Secure credential storage with deliberate backup/transfer exclusions.
- [x] Internet permission; HTTP restricted to the requested LAN host, HTTPS required elsewhere.
- [x] Dedicated interaction/event models and reduction; additive unknown-event tolerance.
- [x] Authenticated REST/WebSocket transport, v1 negotiation, and visible connection/error states.

### Interaction and Presentation

- [x] Create/restore a current conversation, retrieve history, and submit typed requests.
- [ ] Conversation listing/chooser and opening arbitrary historical conversations.
- [x] ViewModel interaction ownership and ordered event reduction outside Activity instances.
- [x] Separate input, response, step/activity, recording, and connection state.
- [ ] Dedicated context, trace, attachment, and playback presentation/state.
- [x] Immediate submission/recording/cancellation feedback; deltas update one response, completion replaces provisional text.
- [x] Live semantic feed and step updates with independent statuses and parent relationships.
- [ ] Richer agent/tool tree presentation (compact nested timing rows and expandable historical step details implemented; deeper agent tree presentation remains).
- [ ] Context provenance, detailed trace, and developer raw-event view (context and per-step trace inspection implemented; developer raw-event view remains).
- [x] Remote cancellation and visible failure states; retry unconfirmed submissions with a durable idempotency key.
- [ ] Retry/regenerate completed requests when supported by the server (currently unavailable in protocol v1).
- [x] Stable server IDs; rendering state and event cursor kept together in memory.
- [x] Reconnect/backoff, replay, duplicate suppression, and gap detection.
- [x] Rotation/Activity recreation continuity; process recreation reloads server snapshots and replays from zero using the saved conversation ID.
- [x] Persist unsent text and pending submissions; server remains authoritative for history.
- [ ] Offline history cache and expired-cursor recovery.

### Voice Input

- [x] Request microphone permission at use, with denial feedback.
- [x] Record 16 kHz mono signed 16-bit PCM, capped at 30 seconds.
- [x] Finish/upload/submit, discard recording, and stop capture when backgrounded.
- [x] Show genuine server transcription events, progress, and text response.
- [x] Preserve uploaded audio ID and submission key for retries; remove local pending audio after acceptance.
- [ ] Streaming microphone transport (server currently supports buffered upload only).
- [x] TTS reception/playback, stop/replay, and audio focus.

## Later Milestones

- [ ] **Attachments:** file/photo pickers, camera, previews/removal, upload progress/retry, and staged share-target content.
- [ ] **Voice extensions:** microphone streaming when available server-side, independent TTS playback, stop/replay, audio focus, and routing.
- [x] **System assistant:** lightweight voice interaction service, session service/session, setup flow, compact UI, and expansion preserving the same interaction.
- [ ] **Device capabilities:** dynamic advertisement, dispatcher, structured results, permission/cancellation handling, notifications/deep links, location, clipboard, intents, and device information.
- [ ] **Screen context:** supported assistant-context/screenshot acquisition, consent, generic protocol conversion, and visible sharing state.
- [ ] **Large screens:** adaptive phone/tablet layouts, multi-pane presentation, and landscape behavior.
- [ ] **Diagnostics:** structured logging categories and sensitive-data redaction.

Custom wake-word support and multiple server profiles remain optional future work, outside the initial milestones.

## Verification Evidence

MVP checks on October 5, 2026, using Android Studio's bundled Java runtime and the API 37 phone emulator:

| Check | Result |
| --- | --- |
| `.\gradlew.bat :app:assembleDebug` | Passed; debug APK built. |
| `.\gradlew.bat :app:testDebugUnitTest` | Passed: 12 tests, including ordered reduction, authoritative final text, deduplication, gaps, nested steps, explicit JSON nulls, STT/failures, bearer transport, PCM upload, and WebSocket cursor. |
| `.\gradlew.bat :app:lintDebug` | Zero errors, 17 warnings; dependency-update and starter-resource warnings remain. |
| `.\gradlew.bat :app:connectedDebugAndroidTest` | Passed: 10 emulator tests covering UI setup/progress/response/recording controls, cancellation callback, Keystore roundtrip, network policy, Activity recreation, PCM capture, mock submission retry/reconnect, and socket reset after completion. Live tests are explicitly excluded. |
| Live typed request | Separate opt-in Android test passed against `10.44.0.33:8081`; progress and response rendered; Activity recreation retained the same interaction. Force-stop/relaunch also restored the completed conversation. A PowerShell WebSocket probe observed ordered progress/delta/completion. |
| Live voice protocol | Generated speech converted to the required PCM format, uploaded, transcribed, and answered successfully by the real server. This was synthetic input, not a physical microphone test. |
| Physical device | User reported a successful phone voice request with visible transcript, execution steps, and final response. A stale post-completion reset banner was reported and fixed below. The fixed APK still needs phone retesting. |
| API 26 / tablet | Not yet verified. |

Mock reconnect coverage disconnects after a partial response, resumes from the cursor, and deliberately repeats a delta to verify deduplication. Failed submission coverage retries the same key. These checks do not prove all real network-transition or server-restart cases.

## Remaining Validation

- [ ] Address or document relevant lint warnings as implementation evolves.
- [x] Product tests for event reduction, ordering, unknown events, gaps, replay/deduplication, and failure rendering.
- [x] Live typed streaming against the actual Assister server.
- [ ] Live reconnect/network changes and remote cancellation during a running request.
- [ ] Test API 26, a recent Android release, and the development target.
- [ ] Test phone/tablet, rotation, background/return, Activity recreation, and process recovery.
- [ ] Exercise network loss/change, server restart, delayed events, concurrent activity, and long responses.
- [x] Basic real microphone speech, server transcription, and response on the user's phone (user-reported).
- [ ] Verify permission denial and background capture behavior on a physical device.
- [ ] Retest post-completion socket handling on the user's phone with the updated APK.
- [ ] Verify attachments, playback/audio focus, tool/TTS failures, and assistant invocation as those features land.

## October 5 development access

A local assister-dev-token.txt file contains the development token configured as a Dockhand stack secret for owner gary. The filename is excluded in .gitignore. Authenticated protocol HTTP 200 and the native Android typed interaction were verified against http://10.44.0.33:8081. The token has no automatic expiration; rotate or remove it after this development week. Do not copy it into source, APK assets, screenshots, or test arguments.

## October 5 Phone Completion Fix

- [x] Stop observing immediately after a terminal interaction event, preserving the authoritative response.
- [x] Ignore socket teardown failures after confirmed completion and clear stale transport error state.
- [x] Keep reconnect/replay behavior for disconnects before completion.
- [x] Add a regression that sends completion, then aborts the socket without a close handshake; verify no banner, resubscription, or duplicate submission.

## Local development upload credential

- October 5, 2026: copied the host's development upload token to the root assister-dev-token.txt file for developer tooling. This is an upload credential, not the app's client API credential, and must not be embedded in the APK.
- Verified the file matches the host's User Secrets value and the existing /assister-dev-token.txt ignore rule covers it. This checkout has no .git directory, so Git ignore behavior could not be checked through Git.


## October 5 App Updates and Debug Publishing

- [x] Settings shows the installed versionName/build and offers manual checks using the saved API server.
- [x] Public update check/download transport uses `/api/updates/assister/android`, independently of chat authentication.
- [x] Stream APK downloads into private cache; verify size and SHA-256 before exposing installation.
- [x] Validate application ID, release version, increasing versionCode, and matching signing certificates before installer handoff.
- [x] Narrow FileProvider directory, install-package permission, unknown-source settings handoff, and Android installer confirmation flow.
- [x] Root `Publish-DebugApk.ps1`: build, read packaged APK version with aapt, raw authenticated PUT, same-host public download, metadata/download size and SHA-256 verification, and explicit 401/409/413 handling. No automatic version changes.
- [x] README documents app update actions, publishing, credentials, version increments, and signing-key preservation.

Validation on October 5, 2026:

- `:app:assembleDebug`: passed; versionName 1.0, versionCode 1, application ID preserved.
- `:app:testDebugUnitTest`: 16 tests passed, including four updater regressions for numeric version comparisons, public transport, verified downloads, corrupt-download cleanup, cross-host rejection, and downgrade rejection.
- `:app:lintDebug`: passed; zero errors, 18 warnings (17 pre-existing warnings plus a String.toUri convenience suggestion).
- `:app:connectedDebugAndroidTest`: 12 tests passed on the API 37 emulator, including settings version/check controls and provider path restrictions; live authenticated chat tests remain excluded.
- `Publish-DebugApk.ps1`: HTTP 201 published version 1.0.0.0 for assister/android. Size 12,885,035 bytes; SHA-256 `d92faf319f25a54273a707ab57c67776c42d09b0c89631438ade043242066155`. Returned metadata and same-host unauthenticated download both verified against the local APK.
- Re-running the script returned HTTP 409 and reported the existing version without changing versions or overwriting the release.

Remaining validation:

- [ ] Complete an on-device upgrade with a future higher versionName/versionCode, including unknown-source permission denial/grant, installer cancellation/confirmation, and post-install launch. The current published build is the same version/build as this checkout; a newer production release was deliberately not invented.
- [ ] Exercise APK download/network failure and installation on API 26 and a physical phone. Signing-key rotation is not supported; exact certificate matching is intentional for these debug releases.


## October 5 Compact Execution Timeline

Implemented:

- Replaced the separate summary-feed-before-steps layout with compact ordered execution rows. The old layout made response completion appear before model/tool work; it did not reflect event chronology.
- Live rows show one-decimal seconds, updating every 100 ms while running. Server timestamps position proportional timing bars, server durations finalize elapsed time, and replay preserves timing. Missing timing is shown as unavailable rather than invented.
- The answer leads the completed card; execution collapses into an expandable step count. Every model/tool row expands to server trace input, output, model/tool metadata, summary and truncation information.
- Context inspection shows selected content, source, model-round usage and provenance. Context updates replace records by ID instead of producing repeated filler rows.
- Historical snapshots can load ordered events and detailed inspection on demand. Sensitive payloads stay in presentation state and are not written to Android logs.

Validation:

- `:app:assembleDebug`: passed; debug APK built, version remains 1.0/build 1.
- `:app:testDebugUnitTest`: 18 tests passed, including timestamp/duration freezing and context update deduplication.
- `:app:lintDebug`: zero errors, 18 existing warnings; removed obsolete feed resources and used plural step summaries.
- `:app:connectedDebugAndroidTest`: 13 API 37 emulator tests passed, including collapse/expand, one-decimal duration, tool payload rendering and absence of misleading completion rows. Mock transport checks also verify inspection content, model rounds, tool payloads/truncation and preservation of final answer/cursor through historical replay.
- Visually inspected an emulator screenshot of expanded tool timing/input/output. This used fixture data, not a live model execution.

Remaining:

- The canonical v1 protocol and current server model adapter expose execution events and diagnostic model/tool data, not streamed reasoning deltas. Genuine live thinking requires a server/provider contract extension; no synthetic thinking is displayed. Existing model-round output remains inspectable through the trace.
- Live authenticated inspection and running-timeline behavior on the user's phone, API 26, and tablet layouts remain unverified in this change. No server deployment or APK publication was performed.

## October 6 Model Round Output and Live Step Details

Implemented:

- Reviewed server commit `f841515` and confirmed it matches fetched `origin/main`. Consulted the updated canonical protocol and actual model/diagnostic publishers.
- User clarified that the desired text is ordinary assistant content from individual model/tool rounds, not provider reasoning. Android labels it MODEL ROUND OUTPUT and keeps it separate from the answer.
- Reduce `step.updated` input/output snapshots by stable step ID with truncation flags. Duplicates remain suppressed through the existing sequence cursor; unknown reasoning events advance the cursor without entering displayed round text or final response.
- Show available round text inline during tool work, collapse it once the interaction completes, and retain all rounds for reopening. Selected rows expose model/tool payloads directly from the stream; authenticated trace inspection remains available for metadata and older-server fallback.
- Feature-detect `execution.details` to avoid repeated whole-trace fetches on every execution event. Context inspection still refreshes on relevant activity, and replay resets/reconstructs streamed details alongside the event cursor.

Validation on October 6, 2026:

- `:app:assembleDebug`: passed; version unchanged at 1.0/build 1.
- `:app:testDebugUnitTest`: 20 tests passed, including separate round text/final answer, independent rounds/tool payloads, truncation, snapshot replacement and replay/deduplication.
- `:app:lintDebug`: passed; zero errors, 18 existing warnings.
- `:app:connectedDebugAndroidTest`: 14 API 37 emulator tests passed. Added live-round presentation, completion collapse/reopen, capability detection, duplicated step updates across reconnect, and historical replay preservation coverage using fixtures/mock transport.

Limitations:

- The new server currently emits ordinary tool-round text through `step.updated.output.assistantContent` when the model round finishes. Token-by-token delivery of ordinary text during tool-capable rounds still needs a separate correlated server event; `reasoning.delta` is a different channel and is deliberately not substituted for the user's requested content.
- Server source and Git revision were verified; deployed server capabilities, live authenticated execution, physical-phone presentation and publication were not verified/performed in this change.

## October 6 Incremental Ordinary Model Output

Implemented:

- Verified server commit `60b8331` matches fetched `origin/main` and read its updated canonical model-output contract.
- Recognize `model.output.stream`; reduce correlated `model.output.started/delta/completed` into separate per-step, per-round segments. Preserve completed/failed/cancelled status and cumulative truncation flags.
- Append fragments verbatim without assumptions about provider token boundaries. Cursor deduplication prevents repeated replay from appending text twice. Replay reconstructs segments alongside step details.
- Render active round text as MODEL ROUND OUTPUT · PROVISIONAL. Prefer streamed segments over diagnostic snapshots, retain text after round failure/cancellation, and preserve completion collapse/reopen behavior. Reasoning and authoritative answer channels remain separate.
- Retain legacy assistantContent/trace fallback. Updated README removes the previously unresolved server-streaming gap.

Validation:

- `:app:assembleDebug`: passed; version remains 1.0/build 1.
- `:app:testDebugUnitTest`: 22 tests passed; added exact fragment concatenation, replay/deduplication, independent rounds, answer/reasoning separation, truncation retention, and failed/cancelled partial-output tests.
- `:app:lintDebug`: zero errors, 18 existing warnings.
- `:app:connectedDebugAndroidTest`: 15 API 37 emulator tests passed, including provisional text before model completion, incremental rendering, streamed-text preference over snapshots, and completion labeling. Existing collapse/reopen and mock reconnect checks continue to pass.

Live deployed-server and physical-phone acceptance remain unverified; no server deployment or APK publication was performed. Server buffering may defer short/trailing fragments until round completion, as documented in the protocol.

## October 6 Updater Release 1.0.1

- Increased versionName to 1.0.1 and versionCode to 2 for the compact UI, live step details, and correlated model-output stream update.
- Ran `Publish-DebugApk.ps1`; APK build passed and upload returned HTTP 201 for release 1.0.1.0.
- Published size: 12,460,977 bytes. SHA-256: `c53e9e3d75e605aedfb4fc233415714ad20e694cceb63be35212bc0b5a4b1228`.
- Publishing script verified returned release metadata and an unauthenticated same-host download against the local APK's size and SHA-256.
- Live public `/api/updates/assister/android/check?currentVersion=1.0` returns updateAvailable=true with the matching 1.0.1.0 release/hash. Checking currentVersion=1.0.1 returns updateAvailable=false.
- API 37 emulator accepted `adb install -r` for this APK. Physical-phone in-app download/installer confirmation and live chat validation remain unverified.
- Existing functional validation before the version-only change: 22 unit tests and 15 emulator tests passed; lint had zero errors and 18 existing warnings.

## October 6 Reconnect Banner Grace Period / Release 1.0.2

- Recoverable live-socket failures continue automatic reconnect/replay immediately but delay the connection-error banner for five seconds. Brief outages show only the existing Reconnecting status.
- Subscription recovery cancels the pending notice and clears the banner; terminal completion and observer cancellation also cancel pending notices. Authentication/not-found/unsupported-version errors still surface immediately. Submission/cancellation failures retain their existing behavior.
- Added a mock transport regression covering a brief quiet reconnect, repeated failures lasting beyond the grace period, visible persistent-outage feedback, recovery clearing, and preserved response/cursor/idempotency behavior.
- Validation: `:app:assembleDebug`, `:app:testDebugUnitTest` (22 tests), `:app:lintDebug` (zero errors, 18 existing warnings), and `:app:connectedDebugAndroidTest` (16 API 37 emulator tests) passed.
- Published versionName 1.0.2 / versionCode 3 through `Publish-DebugApk.ps1`; upload returned HTTP 201 for 1.0.2.0. Size 12,461,425 bytes; SHA-256 `b7dd9ef3f322713359895b8f9a2eba9218cd47194057af6c456b46fcb0ab5249`.
- Metadata and unauthenticated download size/hash verified. Live update check from currentVersion=1.0.1 returns updateAvailable=true and the matching 1.0.2.0 release/hash.
- This addresses immediate transient-error presentation based on the app's reconnect path. The user's intermittent physical-phone network behavior was not reproduced or diagnosed live.

## October 6 Markdown Rendering / Release 1.0.3

- Answers and ordinary model-round output now render CommonMark plus GitHub tables/strikethrough in native Compose. Supports bold, italic, headings, nested bullet/numbered lists (including non-one starting numbers), quotes, inline/fenced/indented code, thematic breaks, links and tables with aligned columns.
- Preserve selectable text and themed typography/colors; wide tables/code scroll horizontally. Streaming reparses changed text so incomplete markup remains readable and formats when completed. Tool input/output and diagnostic metadata retain literal monospace presentation.
- Added CommonMark 0.30.0 core/table/strikethrough dependencies through the version catalog. Raw HTML is literal text, images show alt text without remote fetching, and only HTTP/HTTPS/mailto Markdown links are interactive.
- Validation: assembleDebug passed; 25 unit tests and 17 API 37 emulator tests passed; lint passed with zero errors and 18 existing warnings. Regressions cover bold/code spans, link handling, partial streaming markup, headings/list numbering/table/code rendering and updates. Visually inspected the fixture screenshot `.codex-remote-attachments/markdown-preview.png`.
- Published versionName 1.0.3 / versionCode 4 through Publish-DebugApk.ps1 (HTTP 201). Size 12,587,136 bytes; SHA-256 `fc476ff0c88da0d32f7e4cdbc4a06bd48e888a4ebab68ebaff1c405bc0860a3f`.
- Verified metadata and unauthenticated download size/hash. Live update check from currentVersion=1.0.2 returns the matching 1.0.3.0 release/hash.
- Physical-phone formatting/installation, API 26, and tablet presentation remain unverified. This is a client presentation change; server/protocol behavior is unchanged.

## October 6 Response Audio / Release 1.0.4

Implemented:

- All new typed and voice requests submit speak=true. Authenticated WAV downloads use the fixed interaction audio route, enforce the protocol size limit, validate the WAV header, and retain completed files in private no-backup storage. Download failures keep the text answer intact and expose a local retry.
- Cached replies have a speaker button; playing/preparing replies expose stop. New voice replies autoplay once while the app is foregrounded; typed replies and restored history stay silent. Cached files are reused after reconnect/process restoration.
- Android MediaPlayer uses assistant/speech audio attributes and transient audio focus. Backgrounding, focus loss, headphone removal, another playback, or microphone recording stops playback. Report each replay with a distinct playbackId and serialize started/terminal reports.
- New chat cancels audio downloads, stops playback, and deletes local response audio. Endpoint/credential changes also clear the cache. Server-owned history/audio is preserved.
- No server changes required; verified the current canonical speech/audio/playback contract in the server repository.

Validation and publication results follow below.

- Validation: assembleDebug passed; 27 JVM tests passed; full API 37 emulator suite passed, including real Android MediaPlayer WAV decode/start/completion, speaker/stop controls, silent typed caching, voice autoplay once, ordered replay reports, cache reuse without history autoplay, and clearing during a delayed download. Lint passed with zero errors and 18 existing warnings.
- Published versionName 1.0.4 / versionCode 5 through Publish-DebugApk.ps1. Size 12,606,805 bytes; SHA-256 603155b445bc7d714588f9f47f04370205690cd433542036f3a092a74aa8f7b4. Upload metadata and unauthenticated download size/hash verified. Live update check from currentVersion=1.0.3 returns matching release 1.0.4.0 with updateAvailable=true.
- Physical-phone speaker/Bluetooth/focus behavior and live server TTS execution remain unverified. Playback intentionally stops when backgrounded; returning to the app does not autoplay historical audio.

## October 6 Date Versioning / Release 2026.10.6.1

Implemented:

- [x] Adopt versionName `year.month.day.release` using America/New_York release dates, with a daily release counter starting at 1; document the convention in README.
- [x] Set versionName 2026.10.6.1 and versionCode 6, increasing from the existing 1.0.4 / build 5. Android versionCode remains independently increasing across dates.

Validation:

- `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug` passed. This change only updates release metadata and documentation; instrumented tests were not repeated.
- `Publish-DebugApk.ps1` published 2026.10.6.1 / build 6 successfully (HTTP 201). APK size 12,606,809 bytes; SHA-256 `92852646c31ddb88e379ea0c69fb50836f74aeb348b89cac5c297276ec378d3c`. Returned metadata and unauthenticated same-host download both verified against the local APK.
- Live update check from 1.0.4 returns this dated release with updateAvailable=true; checking 2026.10.6.1 returns updateAvailable=false.
- Phone installer confirmation and post-upgrade behavior remain for user testing.

## October 6 Phone Assistant / Release 2026.10.6.2

Implemented:

- Android-selected VoiceInteractionService, SessionService and Session. The always-available service runs separately in :voice without initializing networking or microphone state. Session uses supported startAssistantActivity with a compact Compose Activity and handles system hide/destroy.
- Settings assistant-role chooser with system-settings fallback. Invocation starts listening once connected/idle, requests microphone permission in the visible Activity, shows progress/Markdown/timeline/playback, and provides finish/discard/cancel/retry/expand/close controls.
- Full-screen and compact clients use one lazily created application-scoped conversation ViewModel. Expand retains microphone/playback foreground ownership and the same interaction without a new submission. Rotation preserves ownership; dismissal/backgrounding releases assistant recording. Existing requests are displayed rather than creating another request on invocation.
- PCM endpointing evaluates 20 ms frames with an adaptive noise floor; requires 120 ms activity, finishes after 1.4 seconds quiet, and rejects eight seconds without speech. Applies to normal voice recording and assistant invocation. Manual finish/discard and the 30-second cap remain. This detects acoustic activity, not word boundaries; physical noise/quiet-voice tuning remains to verify.
- No wire/server changes, speech-recognizer dependency, custom wake word, or screen-context capture. Existing server transcription and TTS remain authoritative.
- User confirmed physical-phone speaker-button playback and voice automatic playback from the previous release.

Validation results and publication follow below.

- Validation: :app:assembleDebug, :app:testDebugUnitTest (34 tests), :app:lintDebug (zero errors, 18 existing warnings), and :app:connectedDebugAndroidTest (24 API 37 emulator tests) passed on the final build. Added short-command/pause/no-speech/click/background/short-read/duration endpoint regressions, compact listening/manual-control tests, and actual system-assist-key invocation after selecting the declared service, dismissal, repeat invocation, and expansion into the same shared conversation. Existing playback/transport/recreation regressions passed.
- Visually inspected the actual system-invoked compact window captured in .codex-remote-attachments/assistant-preview.png. Android API 37 restricts cmd voiceinteraction show for shell; integration tests use the real system assist key instead. Default assistant settings are restored after testing.
- Published versionName 2026.10.6.2 / versionCode 7 through Publish-DebugApk.ps1. Size 13,151,181 bytes; SHA-256 c35eeb333da233157d3aba48f3b36e851125947f7fab0b413f679016131e19d3. Metadata and unauthenticated APK download size/hash verified. Live update check from currentVersion=2026.10.6.1 returns matching 2026.10.6.2 release with updateAvailable=true.
- Remaining phone acceptance: OEM assistant selection/gesture, microphone permission on first invocation, real speech endpoint timing in quiet/noisy environments, and compact-response playback. API 26 and locked-device/OEM behavior remain unverified; lock-screen launch is not advertised. Existing playback on the user's phone was confirmed for the preceding release.

## October 6 Cancel Failed Send / Release 2026.10.6.3

- Added Cancel send for pending submissions in full chat and compact assistant, including offline. Disabled only during submission/recording/local cleanup to avoid racing acceptance or an in-flight upload.
- Discard removes durable pending body/idempotency key and the pending PCM file, clears the send error, clears failed typed text, and unlocks fresh input. Voice cancellation preserves an independent typed draft. It never replays the uncertain POST to discover an ID. Already accepted server work remains separate and can use the existing remote Cancel control when its interaction is known.
- Added regressions for both UI callbacks offline, typed/voice cleanup and restoration without resurrecting pending state.
- User confirmed the phone assistant works in the preceding release.

- Validation: assembleDebug, 34 JVM tests, lint (zero errors, 18 existing warnings), and full 28-test API 37 emulator suite passed. After a final button-spacing adjustment, rebuilt and repeated lint plus eight affected chat/assistant/pending-cancellation tests; all passed. Phone failed-send cancellation remains to verify.
- Published versionName 2026.10.6.3 / versionCode 8 through Publish-DebugApk.ps1. APK size 12,758,382 bytes; SHA-256 790ed81a45f0938386ab1c2c05516987eba974d603a94495db20425b41c68610. Returned metadata and unauthenticated APK download size/hash verified. Live update check from 2026.10.6.2 returns matching 2026.10.6.3 release with updateAvailable=true.
