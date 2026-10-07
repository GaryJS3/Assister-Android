# Assister Android

Native Kotlin Android client for Assister, using Jetpack Compose and Material 3.

## Project plan and current status

Read [progress.md](progress.md) for implemented work, remaining milestones, and verification evidence. Agents should consult it before starting work and update it when implementation or validation changes; it is the implementation tracker.

The [project specification](docs/assister-android-project.md) describes the intended product and implementation phases. It is a planning reference, not a statement of implemented functionality or an instruction to implement all features at once.

The MVP supports typed requests and buffered voice input, live server activity, streamed answers, cancellation, and reconnect/replay. It creates or restores one current conversation and retrieves its server history. The default endpoint is `http://10.44.0.33:8081`; change it and enter your client bearer token in Settings. Tokens are encrypted using Android Keystore and excluded from backup/transfer. They are not embedded in the APK.

Brief live-feed disconnects show Reconnecting while automatic replay restores the stream. The connection-error banner appears only after five seconds without recovery; authentication and unsupported-protocol errors remain immediate.

Tap **Send** for text, or **Record voice**. Voice finishes automatically after roughly 1.4 seconds of quiet following detected speech; **Finish & send** remains available. Eight seconds without speech stops capture without submitting, and recordings always stop at 30 seconds; **Discard** cancels capture. Backgrounding the app discards an active recording. Voice is uploaded as 16 kHz mono PCM before server transcription, matching the current protocol. Every new typed or voice request asks the server for speech and downloads the response WAV into private storage. Typed replies stay silent; voice replies play automatically while the app is open. Use the speaker button on a message to replay its cached audio, or its stop button to stop playback. Backgrounding, starting another recording, losing audio focus, or unplugging headphones stops playback. Restored history never autoplays. Starting a new chat deletes cached response audio and cancels unfinished downloads. Audio failures do not replace the text answer.

The foundation matches the plan:

| Area | Current foundation | Assessment |
| --- | --- | --- |
| Language and UI | Kotlin, Compose, Material 3, AndroidX | Keep this stack. |
| Device baseline | `minSdk 26` | Matches the specification. |
| SDK configuration | Compile/target SDK 37 | Validate through the installed SDK and build before changing versions. |
| Application identity | `net.thegary.assister` | Keep it; the plan's `com.gjnet.assister` package is illustrative. |
| Modules | Single `app` module | Sufficient initially; isolate responsibilities with packages. |
| Tests | JUnit 4, AndroidJUnit4, Compose UI tests, MockWebServer | Reducer, authenticated transport, replay/retry, UI, Keystore, and emulator capture checks. |

## Architecture direction

Responsibilities are separated under `net.thegary.assister`:

- `protocol`: wire models, event decoding, and compatibility handling.
- `network`: REST, WebSocket, uploads, and reconnect transport.
- `interaction`: ordered event reduction, replay deduplication, and shared interaction state.
- `settings`: configurable server endpoint, encrypted authentication, and recovery identifiers.
- `ui`: simple conversation, progress feed, input controls, and settings.
- `voice`: bounded PCM microphone capture, local energy-based silence endpointing, cached speech playback and audio focus.
- `assistant`: lightweight voice interaction service/session and compact assistant Activity.

Keep transport and active interaction ownership outside `MainActivity`. AssisterApplication lazily owns one process-scoped conversation ViewModel shared by full-screen and compact assistant Activities. Expanding preserves recording, playback, state, and the active interaction without resubmitting. Process recreation reloads saved identifiers and reconciles with server history. The always-available voice service runs in a separate :voice process and never creates the conversation runtime.

Keep response text, activity trees, context, trace, connection, and audio state separate. Render response deltas and progress promptly; speech must not delay text. Durable conversation history remains server-owned.

Android recognizes `execution.details` and `model.output.stream`. It reduces `step.updated` input/output snapshots and `model.output.started/delta/completed` segments by stable step ID. Ordinary model text appears provisionally under its model round while tools run, separately from reasoning and the final response, and remains available in expanded completed activity. Fragments are appended exactly as delivered; server redaction buffering can change fragment boundaries. Older servers fall back to `output.assistantContent` and authenticated trace inspection.

Answers and ordinary model-round text render Markdown using native Compose text: bold/italic/strikethrough, headings, nested bullet/numbered lists, quotes, code, links and GitHub-style tables. Wide tables and code scroll horizontally; rendered text remains selectable. Incomplete streamed markup is reparsed as text arrives. Tool payloads remain literal monospace data. Raw HTML is displayed as text and image markup shows alt text without fetching remote images.

## First milestone

Combine foundation, typed conversation, and live activity work into one demonstrable flow: configure a server, authenticate, open a conversation, send text, watch progress and response streaming, and recover after rotation or reconnect without duplicating the interaction.

Before implementing wire behavior, consult the [canonical rich-client protocol](https://github.com/GaryJS3/Assister/blob/main/docs/rich-client-protocol.md) and verify available server behavior. The copied specification's examples are not authoritative protocol definitions.

Internet permission is declared; microphone permission is requested when recording. Cleartext traffic is allowed only for `10.44.0.33`; other server hosts require HTTPS. Private preferences and recordings are excluded from cloud backup and device transfer. Android assistant services are declared with BIND_VOICE_INTERACTION protection. Device capabilities are not yet advertised.

Attachments, device capabilities, and screen context follow this MVP. Durable recovery stores the conversation and pending submission key; after process recreation it fetches authoritative snapshots and replays events from zero. Cursors remain in memory alongside rendering state, avoiding a persisted cursor with missing UI state. A failed submission's **Retry send** reuses its key instead of creating a second request.

## Phone assistant

In Assister Settings, tap **Use as phone assistant**, then select Assister in Android’s default assistant chooser. On older/OEM devices use Android Settings → Apps → Default apps → Digital assistant app. Configure the server/token first; microphone permission is requested when listening starts.

Invoke with the phone’s assistant gesture/button. The compact window starts listening when connected and idle, submits after the end-of-speech pause, streams the response, and plays its audio. Invoking during an existing request displays that request. **Expand** opens the same conversation in the full app. Closing/backgrounding discards unsubmitted assistant recording; submitted server work continues. The compact window retains the existing Markdown, timeline and speaker controls.

Android’s assistant invocation APIs do not endpoint raw PCM automatically. Our detector evaluates 20 ms PCM energy frames, requires 120 ms of sustained activity, and finishes after 1.4 seconds of quiet. It distinguishes activity from silence rather than recognizing words; loud background audio can delay completion and very quiet speech can be missed. Manual finish/discard and the 30-second cap remain available. Transcription stays on the existing server. No custom wake word or screen-context capture is enabled.

## Development

Open the project in Android Studio. Use the checked-in Gradle wrapper and installed Android SDK. In PowerShell, if Java is not already configured:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Run `.\gradlew.bat :app:connectedDebugAndroidTest` with a device or emulator. See [AGENTS.md](AGENTS.md) for contributor guidance.

Live-server tests are excluded from the normal suite. `LiveServerTest` explicitly requires the `liveServer=true` instrumentation argument and a transient `files/live-test-token.txt` inside the app's private directory; it deletes that plaintext file and stores the credential through the normal encrypted settings path. Run live tests separately from Gradle device tests. They create a real conversation and send a benign verification request. Never pass credentials in instrumentation arguments or commit them.

## App updates and publishing

In **Settings**, the app shows its installed version and build number. **Check for updates** queries the saved API server at `/api/updates/assister/android/check`. Save a changed server before checking. **Download update** streams the APK into a private cache directory and verifies its size and SHA-256, application ID, newer versionCode, and matching signing certificate. **Install update** opens Android's installer. If prompted, allow Assister to install unknown apps, return to Settings, and tap **Install update** again. Android requires user confirmation; cancelling leaves the current app installed. Checks and downloads send no chat or upload credential. Checking is manual; there is no background polling.

Publish the current debug APK from PowerShell:

```powershell
.\Publish-DebugApk.ps1
```

The default host is `http://10.44.0.33:8081`; optionally pass `-HostUrl https://your-host/`. Put the upload credential in the already ignored `assister-dev-token.txt` at the repository root. This credential is separate from the client bearer token entered in Android Settings. The script trims it, builds with `:app:assembleDebug`, reads the actual APK manifest version using the SDK's `aapt.exe`, and uploads the raw APK bytes with PUT. It stops on a failed build, rejected token, or other upload error. It checks published metadata against the APK's size/SHA-256, then downloads the returned URL from the same host without authentication and verifies the bytes again. Temporary verification downloads are removed.

Versions use `year.month.day.release`, for example `2026.10.6.1`, using the release date in America/New_York. Start the final component at 1 each day and increase it for additional releases that day. Android's `versionCode` is a separate increasing integer and never resets with the date. This fits the server's numeric `major.minor[.build[.revision]]` format. A 409 reports that the version already exists and stops without changing versions. Before each subsequent release, explicitly increase both `versionName` and `versionCode` in `app/build.gradle.kts`. Keep `net.thegary.assister` and the same signing key (including preserving the developer debug keystore for these debug releases). This implementation checks exact signing-certificate equality; signing-key rotation is not supported. SHA-256 verifies integrity against server metadata; Android signing verifies package compatibility. The permitted LAN HTTP connection assumes a trusted network; use HTTPS elsewhere.

Failed submissions offer **Cancel send** alongside retry, including while offline in chat and assistant mode. It clears the local retry/key and pending recording so you can start again. Cancelling a typed send clears its draft; cancelling a voice send preserves any independent typed draft. It does not resubmit or guarantee stopping work the server may already have accepted; known active interactions have a separate **Cancel** control.
