# Assister Android Project

## Status

**Project / Implementation Specification**

Current implementation status and verification evidence are tracked in [progress.md](../progress.md). Agents should read that tracker before work and update it after implementation or validation changes. Checklists in this specification define acceptance requirements; use the tracker to determine what is actually complete. See [README.md](../README.md) for how this plan maps to the existing Android foundation.

This document defines the goals, architecture, behavior, and implementation guidance for the standalone **Assister Android application**.

The Android application is a separate project from the Assister server.

The server-side rich-client protocol is maintained in the main Assister repository and is the canonical source of truth for protocol behavior:

**https://github.com/GaryJS3/Assister/tree/main/docs/rich-client-protocol.md**

> **Important:** Before implementing or modifying any protocol-facing Android feature, consult the current rich-client protocol document above. It is expected to evolve as Assister is implemented. Do not assume examples or protocol details copied into this document are more current than the canonical protocol specification.

---

# 1. Project Goal

Build a native Android client for Assister that can function as:

1. A full rich Assister chat/voice client
2. The user's Android system assistant
3. A device capability provider to Assister
4. A voice satellite/client with microphone and speaker support
5. A highly responsive real-time view into what Assister is doing

The application should work well on phones and tablets.

The experience should make Assister feel **live**.

When the user speaks, the transcription should visibly develop.

When Assister begins reasoning or routing the request, the user should see that progress.

When Assister invokes a tool or agent, that should appear while it is happening.

When the final LLM response is generated, the text should stream onto the screen as tokens/chunks arrive.

TTS may naturally lag behind the streamed final text. This is acceptable. The text does not need to wait for TTS generation.

The UI should never intentionally hide useful progress merely so that the final interaction appears more polished.

---

# 2. Core UX Principle: Immediate Feedback

Responsiveness and feedback are first-class requirements.

The application should avoid states where the user asks something and sees a static spinner while substantial work is happening.

Prefer:

```text
Listening...

"How hot did the off..."

"How hot did the office get today?"

Transcription complete

Understanding request...

Using LLM...

Calling Home Assistant history...

Retrieved office temperature history

Calculating peak temperature...

Generating response...

"The office peaked at..."

"The office peaked at 78.4°F..."

"The office peaked at 78.4°F around 3:20 PM."

Generating speech...

Speaking...
```

over:

```text
Thinking...
Thinking...
Thinking...
Thinking...

The office peaked at 78.4°F around 3:20 PM.
```

The protocol already exists to support streaming progress. The Android application should consume and expose it aggressively.

---

# 3. Responsiveness Requirements

## 3.1 Local feedback

User actions should produce immediate local feedback without waiting for the server.

Examples:

- Microphone button changes state immediately when pressed
- Input submission immediately appears in the conversation
- Attachment selection immediately displays a pending attachment
- Cancel immediately changes the UI to "Cancelling..."
- Connection loss immediately displays degraded/reconnecting state

Do not wait for a server round trip before acknowledging a local user action.

## 3.2 Incoming event rendering

Protocol events should be reflected in the UI as soon as practical after receipt.

Do not unnecessarily batch:

- STT partials
- progress events
- tool events
- agent events
- response deltas

Small coalescing may be used if required to prevent UI thrashing, but it should not create visibly delayed updates.

## 3.3 Streaming response

Final LLM output must be displayed incrementally.

Do not wait for:

- full model completion
- TTS completion
- tool trace finalization
- interaction completion

before displaying available response text.

## 3.4 TTS independence

Text and TTS are separate presentation channels.

Expected behavior:

```text
LLM response.delta
        |
        +--> immediately render text
        |
        +--> server/TTS pipeline generates audio
                        |
                        +--> play audio when available
```

It is acceptable and expected for text to be visibly ahead of speech.

Never delay streamed text merely to synchronize it with TTS.

---

# 4. Technology Constraints

Use:

- **Kotlin**
- **minSdk 26** / Android 8.0
- Native Android application
- AndroidX

Kotlin is the primary implementation language for the application. New application code should be written in Kotlin unless there is a specific interoperability reason to use Java.

The UI technology should follow the existing Android project architecture. Do not rewrite working UI solely to switch between Jetpack Compose and Android Views/XML. If a new UI layer is being designed, use the project's established UI stack consistently.

Target and compile SDK should use a current supported Android SDK at development time while preserving `minSdk 26`.

---

# 5. General Architecture

Suggested architecture:

```text
Android App
    |
    +-- UI Layer
    |     +-- Conversation Activity
    |     +-- Assistant Overlay / Session UI
    |     +-- Settings
    |     +-- Context / Trace Views
    |
    +-- Interaction Layer
    |     +-- Conversation Controller
    |     +-- Interaction Controller
    |     +-- Event Reducer / State Store
    |
    +-- Assister Client
    |     +-- REST Client
    |     +-- WebSocket Client
    |     +-- Audio Transport
    |     +-- Attachment Upload
    |     +-- Reconnect / Replay
    |
    +-- Android Integration
    |     +-- VoiceInteractionService
    |     +-- VoiceInteractionSessionService
    |     +-- VoiceInteractionSession
    |     +-- Share Target
    |     +-- Notifications
    |     +-- Device Capabilities
    |
    +-- Media
          +-- Microphone Capture
          +-- Audio Playback
          +-- Attachment Handling
```

Keep the networking/protocol layer independent of individual Activities and Views.

The same live interaction should be able to move between:

- compact assistant presentation
- full-screen conversation UI
- tablet UI

without creating another interaction.

---

# 6. Canonical Protocol

The Android application is a client of the Assister rich-client protocol.

Canonical documentation:

https://github.com/GaryJS3/Assister/tree/main/docs/rich-client-protocol.md

The Android codebase should treat protocol models as a dedicated module/package rather than scattering JSON parsing across the UI.

Suggested package organization:

```text
com.gjnet.assister
    |
    +-- protocol
    |     +-- models
    |     +-- events
    |     +-- serialization
    |
    +-- network
    |     +-- rest
    |     +-- websocket
    |     +-- media
    |
    +-- interaction
    +-- ui
    +-- voice
    +-- capabilities
    +-- attachments
    +-- settings
```

If the server protocol changes, protocol-specific changes should be mostly isolated to the protocol/network layers.

---

# 7. Protocol Compatibility Rules

The Android client should:

- Advertise/support the protocol version defined by Assister
- Handle additive unknown event types gracefully
- Never crash because a newer server sends an unknown optional event
- Preserve event ordering
- Track interaction sequence numbers
- Support missed-event replay after reconnect
- Distinguish live events from restored historical state
- Use stable conversation IDs
- Use stable interaction IDs
- Treat server state as authoritative after reconciliation

Do not invent Android-specific versions of existing Assister concepts if the common protocol already represents them.

---

# 8. Primary Application Modes

The same backend/client code should support several UI modes.

## 8.1 Full conversation client

Normal app launch.

Provides:

- Conversation history
- Text input
- Voice input
- Attachments
- Streaming STT
- Live activity/progress
- Streaming answer
- TTS playback
- Context inspection
- Trace inspection

## 8.2 System assistant

The user should be able to choose Assister as Android's default assistant.

Expected invocation sources may include:

- Long press power button
- System assistant gesture
- Other OEM/system assistant invocation mechanisms

Use Android's supported assistant APIs rather than hacks.

The app should implement the appropriate:

- `VoiceInteractionService`
- `VoiceInteractionSessionService`
- `VoiceInteractionSession`

The top-level `VoiceInteractionService` must remain lightweight.

Heavy UI, interaction handling, network execution, etc. should occur in appropriate session/application components rather than turning the always-available service into the application runtime.

Android documentation explicitly recommends keeping `VoiceInteractionService` lightweight because the selected service is kept running by the system.

## 8.3 Compact assistant presentation

System-assistant invocation should not require launching the full-screen chat interface.

A compact assistant presentation should show:

```text
+--------------------------------------+
| Assister                             |
|                                      |
| "How hot did the office get..."      |
|                                      |
| Listening / Transcribing / Running   |
|                                      |
| ✓ Understood request                 |
| ✓ Checked Home Assistant             |
| ● Generating response...             |
|                                      |
| The office peaked at 78.4°F...       |
|                                      |
|                        [Expand]       |
+--------------------------------------+
```

This is conceptual, not a required exact layout.

The compact presentation should consume the exact same interaction/event stream as the full conversation client.

Do not create a separate assistant protocol.

## 8.4 Tablet interface

The application should adapt cleanly to large screens.

A tablet may show multiple panes simultaneously:

```text
+-------------------+----------------------------+------------------+
| Conversations     | Conversation               | Activity/Context |
|                   |                            |                  |
| Office temp       | You                        | Live activity    |
| Server problem    | ...                        |                  |
| Grocery list      |                            | Context used     |
|                   | Assister                   |                  |
|                   | ...                        | Trace            |
+-------------------+----------------------------+------------------+
```

Do not simply stretch a narrow phone layout across a large screen.

Exact adaptive UI implementation may come after the core phone experience, but the architecture should not make tablets difficult later.

---

# 9. Conversation Screen

The main screen should combine conversation and live execution state.

Suggested phone concept:

```text
+--------------------------------------+
| Assister                    Menu     |
+--------------------------------------+
|                                      |
| You                                  |
| What's wrong with this?              |
| [ image attachment ]                 |
|                                      |
| Assister                             |
|                                      |
| ✓ Transcribed speech                 |
| ✓ Examined image                     |
| ✓ Checked device state               |
| ● Looking through history...         |
|                                      |
| The problem appears to be...         |
|                                      |
| Context used                  View   |
| Trace                         View   |
|                                      |
+--------------------------------------+
| Attach   Ask Assister...   Mic Send |
+--------------------------------------+
```

---

# 10. Voice Input

Voice interaction is a major feature.

The application should support:

- Tap-to-talk
- Assistant-invocation voice capture
- Visible recording state
- Live STT partial text
- Final STT text
- Cancel recording
- Clear error handling
- Audio streaming to Assister

The application should not perform its own independent semantic interpretation when Assister is available.

Assister owns:

- STT backend selection
- intent/model routing
- tools
- agents
- response generation
- TTS

The Android application captures/presents the interaction.

---

# 11. STT Presentation

Partial transcription should visibly update during speech.

Example:

```text
Listening...

How hot

How hot did

How hot did the office

How hot did the office get today?
```

When Assister marks STT final:

```text
You
How hot did the office get today?
```

The user should always have a way to see what Assister actually heard.

If the protocol supports STT metadata later, the UI may optionally expose it in the detailed trace.

---

# 12. Live Activity / Progress

This is a core feature, not optional debug UI.

The normal UI should show meaningful execution events while the request runs.

Examples:

```text
Understanding request...
Using intent engine...
Passing request to LLM...
Searching Home Assistant entities...
Running get_history...
Retrieved 288 temperature samples
Analyzing result...
Generating response...
```

The Android client should not attempt to derive these strings from raw logs.

Use the user-facing progress/step information supplied by Assister.

---

# 13. LLM and Routing Feedback

When the protocol exposes these stages, the user should be able to see them.

Examples:

```text
Intent engine selected Home Assistant route
LLM processing request
LLM requested get_history
Tool completed
LLM processing tool result
```

The normal view should remain concise.

Detailed implementation information belongs in expandable trace/debug views.

The user should be able to tell whether the application is:

- recording
- transcribing
- routing
- waiting on a model
- running a tool
- running an agent
- generating final text
- generating TTS
- speaking
- reconnecting
- failed

There should not be one generic "thinking" state for all of these.

---

# 14. Concurrent Activity

Assister may execute tools or agents concurrently.

The Android UI must not assume all progress is a single linear list.

Support parent/child or parallel activity.

Example:

```text
Researching request
|
+-- Home Assistant
|   +-- ✓ Temperature history
|
+-- Logs
|   +-- ● Searching HVAC events
|
+-- Documents
    +-- ✓ Equipment reference found
```

The exact visual representation can evolve.

The internal state model must support it from the beginning.

---

# 15. Final Response

The response should stream into the existing assistant message as chunks arrive.

Example:

```text
The

The office

The office peaked at

The office peaked at 78.4°F

The office peaked at 78.4°F around 3:20 PM.
```

Do not create a separate chat message for every delta.

Maintain one in-progress response object and append/update it.

After completion, it becomes the normal persisted assistant response.

---

# 16. Spoken Response

If TTS is enabled for the interaction:

1. Stream final text immediately
2. Receive/generate TTS through Assister
3. Begin playback as soon as usable audio is available
4. Continue displaying full text
5. Allow stop/replay

The UI may therefore show:

```text
Response text complete
Generating speech...
Speaking...
```

or begin speaking while final audio continues streaming if the server protocol supports it.

Text availability should never depend on TTS success.

---

# 17. Attachments

Support:

- Camera photo
- Existing photo
- File
- Screenshot
- Shared content
- Clipboard/pasted content where Android APIs permit

Attachment UI should show pending attachments before submission.

Each attachment should support:

- Preview when practical
- Remove before sending
- Upload progress when useful
- Failed upload state
- Retry

---

# 18. Camera

Allow taking a picture directly for an Assister request.

Example:

```text
User taps Attach
    |
    +-- Camera
    |
captures image
    |
preview
    |
send with interaction
```

The image should enter Assister using the common attachment protocol.

Do not create a special camera-specific server request if generic attachments already handle images.

---

# 19. Photos / Files

Use Android's supported picker mechanisms.

Avoid requesting broad storage permissions when the platform photo/file picker can provide the selected content.

The app should be able to attach:

- Images
- PDFs
- Text files
- Other server-supported files

Server capability/validation should determine final accepted formats.

---

# 20. Screenshots

Support screenshots through appropriate Android-supported paths.

Important likely paths include:

1. User takes a normal Android screenshot and shares it to Assister
2. User selects an existing screenshot
3. Assister receives screen context while acting as the system assistant where Android permits it
4. Optional explicit screen-capture functionality using supported Android APIs and required user consent

Do not use AccessibilityService merely as a screenshot bypass.

Screen capture and screen context must respect Android permissions and platform restrictions.

---

# 21. Android Share Target

Assister should appear as a share destination where practical.

Examples:

```text
Chrome
    Share
      -> Assister

Photos
    Share
      -> Assister

Files
    Share
      -> Assister

Selected text
    Share
      -> Assister
```

Supported incoming content should include:

- text
- URLs
- images
- files

Shared content should open/create a conversation with the attachment/content staged for the user.

Prefer allowing the user to add a question rather than automatically submitting shared content immediately.

---

# 22. Context Used

Each interaction should provide access to the context Assister reports as used.

Example:

```text
Context Used

USER
  Screenshot.png

HOME ASSISTANT
  sensor.office_temperature
  Office temperature history

CONVERSATION
  Previous 4 messages

TOOLS
  get_history
```

Selecting an item should expose more detail when allowed by the protocol.

The Android app should render structured provenance from Assister.

Do not attempt to reconstruct context by scraping the visible trace.

---

# 23. Trace / Debug View

Provide a detailed interaction trace.

This is particularly useful during Assister development.

Suggested modes:

```text
Activity
Detailed Trace
Raw
```

### Activity

Human-friendly execution progress.

### Detailed Trace

Technical events such as:

```text
20:14:12.114 interaction.started
20:14:12.183 model.started
20:14:12.491 tool.started get_history
20:14:12.793 tool.completed 301 ms
20:14:12.802 model.started
20:14:13.127 response.delta
```

### Raw

Protocol/raw structured event data where practical.

The raw view is primarily a developer feature.

---

# 24. Expandable Execution Detail

Normal users should not be flooded by raw implementation data.

Use progressive disclosure.

For example:

```text
✓ Checked Home Assistant
```

can expand into:

```text
Tool
  get_history

Duration
  301 ms

Input
  [Inspect]

Result
  288 samples

Context contribution
  [Inspect]
```

This preserves responsiveness without making the default chat unreadable.

---

# 25. Connection State

Connection state should always be observable when relevant.

Possible states:

```text
Connected
Connecting
Reconnecting
Offline
Authentication required
Server unavailable
Protocol incompatible
```

Do not silently fail WebSocket reconnection.

If live streaming is unavailable but REST history still works, the UI should clearly show degraded state.

---

# 26. Reconnection / Event Replay

The application must survive:

- Wi-Fi to cellular transitions
- temporary server loss
- Android process pauses
- app background/foreground
- screen rotation
- Activity recreation

For active interactions:

```text
Receive event sequence 123
        |
connection lost
        |
server continues interaction
        |
client reconnects
        |
request events after 123
        |
replay 124...
        |
resume live stream
```

The user should not lose the interaction merely because the Activity was recreated or network connectivity changed.

---

# 27. Local State Model

Do not bind UI widgets directly to WebSocket callbacks.

Incoming protocol events should update an interaction state model/store.

Conceptually:

```text
WebSocket event
      |
      v
Protocol parser
      |
      v
Event reducer
      |
      v
Interaction state
      |
      +--> Full conversation UI
      +--> Compact assistant UI
      +--> Trace UI
      +--> Notification if needed
```

This makes reconnect/replay and multiple presentations far easier.

---

# 28. Threading

Never perform:

- networking
- JSON parsing of large payloads
- audio encoding
- image processing
- file reads
- database operations

on the Android main/UI thread.

UI state updates must return to the main thread.

Fast streaming should not cause excessive layout churn.

---

# 29. System Assistant Integration

Use official Android voice interaction APIs.

Primary components:

```text
AssisterVoiceInteractionService
AssisterVoiceInteractionSessionService
AssisterVoiceInteractionSession
```

The selected `VoiceInteractionService` is kept available by Android and should remain lightweight.

The session should handle active user interaction.

The app should support being chosen for Android's Assistant role where supported by the device.

System assistant functionality should degrade gracefully on OEM devices that restrict or alter standard assistant behavior.

---

# 30. Assistant Context

When invoked as the system assistant, use supported Android assistant-context mechanisms where permitted.

Potential context can include:

- foreground application assist structure
- selected/visible content
- screen context
- screenshot supplied through the assistant APIs

This information should be converted into Assister's generic context/attachment model.

Do not make the Assister server depend on Android-specific object formats.

---

# 31. Device Capability Provider

The Android app should advertise supported capabilities according to the current rich-client protocol.

Potential capabilities include:

```text
audio.input
audio.output
display

attachments.image
attachments.file

camera.capture

screenshot.capture
screen_context.read

notifications.show

location.read

clipboard.read
clipboard.write

intent.launch

device.info
```

The exact canonical names must come from the current protocol documentation.

Do not hard-code this list as protocol truth if the server specification has changed.

---

# 32. Dynamic Capability Availability

A capability being supported by the app does not mean it is currently usable.

Examples:

```text
camera supported
camera permission denied

location supported
location disabled by user

screen context supported
not available for current invocation
```

Capability responses must be able to fail cleanly.

The app should expose enough state for Assister to distinguish:

- unsupported
- permission required
- temporarily unavailable
- cancelled by user
- failed

---

# 33. Permissions

Use least privilege.

Request permissions only when needed.

Potential permissions/capabilities include:

- microphone
- camera
- notifications
- location

Do not request all sensitive permissions during first launch simply because the app may use them later.

Explain why a permission is needed at the point where the user enables/uses the related feature.

Android background microphone, camera, location, and foreground-service behavior varies by API level. Implement using current Android platform requirements rather than assuming API 26 behavior applies to newer versions.

---

# 34. Background Operation

Do not create a permanently running generic foreground service merely to keep the application alive.

Use Android-supported lifecycle mechanisms.

A foreground service may be appropriate for an active user-visible operation such as ongoing microphone/audio work where required.

Modern Android imposes additional foreground-service restrictions and service-type requirements.

The fact that `minSdk` is 26 does not remove target-SDK restrictions on newer Android versions.

---

# 35. Audio Capture

Create an audio abstraction that can support whatever format the current Assister protocol expects.

Suggested responsibility:

```text
AudioCapture
    start()
    stop()
    cancel()
    stream frames/chunks
    expose level/state
```

The UI should be able to display:

- recording state
- optional input level
- transmission state

Avoid unnecessarily buffering an entire voice request before sending if the server supports streaming STT.

---

# 36. Audio Playback

Provide a playback abstraction for Assister speech.

Required behavior:

- play
- pause/stop
- replay
- interruption handling
- audio focus handling
- headset/Bluetooth routing through normal Android behavior

If streaming TTS is supported, playback should begin as soon as sufficient data is available rather than waiting for a complete large file.

---

# 37. Audio Focus

Assister must cooperate with other Android audio.

During spoken output:

- request appropriate audio focus
- respect focus loss
- avoid fighting navigation/music/calls
- release focus when finished

Voice capture should also behave predictably around calls and other microphone users.

---

# 38. Device Actions

A future Assister request may ask the Android client to perform an action.

Examples:

```text
Show notification
Get current location
Read clipboard
Write clipboard
Open URL
Launch app/intent
Capture image
```

All actions should flow through a capability dispatcher.

Conceptually:

```text
device.request
      |
      v
CapabilityDispatcher
      |
      +-- LocationCapability
      +-- NotificationCapability
      +-- ClipboardCapability
      +-- IntentCapability
      +-- CameraCapability
      |
      v
device.response
```

Do not place a giant switch statement inside the WebSocket client.

---

# 39. Device Action Safety

Some actions should require immediate user approval depending on risk and context.

Examples:

- camera capture
- screen capture
- sensitive clipboard access
- launching certain intents
- location when not clearly user-initiated

The application should preserve Android's security model rather than attempting to silently bypass it.

Server trust does not replace Android permission/user-consent requirements.

---

# 40. Notifications

The future client should be capable of receiving Assister-originated notifications if supported by the server protocol.

Notifications may represent:

- requested reminders
- asynchronous Assister results
- proactive alerts
- condition-triggered events

Tapping a notification should deep-link to the relevant:

- conversation
- interaction
- action

where possible.

Do not design notifications as an independent conversation system.

---

# 41. Authentication and Server Configuration

The app needs configurable Assister server connectivity.

Settings should include at least:

```text
Server URL
Authentication
Connection status
Client/device name
```

Potential future settings:

```text
Default TTS behavior
Auto-play voice response
Microphone behavior
Assistant behavior
Debug trace visibility
Capability permissions
```

Do not place credentials in query strings.

Store secrets using Android-supported secure storage.

---

# 42. Multiple Servers

Not required for the first version.

However, avoid deeply coupling the code to a compile-time server URL.

A single configurable Assister endpoint is sufficient initially.

Multiple profiles can be added later if needed.

---

# 43. Conversation Persistence

The server is authoritative for durable conversation history.

The Android app may maintain a local cache for:

- responsive startup
- offline display
- pending interactions
- recent events

Do not create an independent competing conversation database that can diverge from Assister.

Local state should be viewed as cache/pending client state unless the protocol defines otherwise.

---

# 44. Offline Behavior

Initial offline behavior can be simple.

When the server is unavailable:

- Existing cached conversations may remain viewable
- New server-dependent requests should clearly indicate they cannot run
- Pending unsent typed text should not be discarded
- Attachments should not silently disappear
- Reconnection should be automatic with appropriate backoff

Do not pretend a request was accepted if it never reached Assister.

---

# 45. Cancellation

The user should be able to cancel at every meaningful stage:

```text
Recording
Transcribing
LLM execution
Tool/agent execution
Response generation
TTS generation
Speaking
```

Cancellation feedback should be immediate.

Example:

```text
Cancel pressed
    |
UI immediately: Cancelling...
    |
send cancellation request
    |
server confirms
    |
Cancelled
```

If the remote task cannot be interrupted instantly, the UI should still show that cancellation has been requested.

---

# 46. Retry / Regenerate

Support server-defined retry/regenerate semantics.

Do not simply duplicate the previous user message and pretend that is equivalent if the protocol exposes a real retry endpoint.

The trace should distinguish retries from the original interaction.

---

# 47. Errors

Errors should be visible and actionable.

Prefer:

```text
Home Assistant history request failed
[Details] [Retry]
```

over:

```text
Something went wrong
```

Protocol errors should be mapped to user-friendly messages while retaining detailed technical data for the trace/debug view.

---

# 48. Developer / Debug Mode

Provide a developer mode that can expose:

- WebSocket state
- Protocol version
- Raw event stream
- Sequence numbers
- Interaction ID
- Conversation ID
- Tool request/response details exposed by server
- Timing
- Client capability advertisement
- Device request/response messages
- Audio transport state

This is useful while Assister is still under active development.

Avoid requiring ADB/logcat for every protocol debugging session.

---

# 49. Logging

Use structured application logging.

Logging should distinguish:

```text
NETWORK
WEBSOCKET
PROTOCOL
AUDIO
STT
INTERACTION
CAPABILITY
VOICE_SERVICE
UI
```

Never log authentication secrets.

Avoid dumping sensitive:

- screenshots
- clipboard contents
- location
- raw images
- private tool results

to normal Android logs.

---

# 50. Orientation and Lifecycle

An active interaction must survive:

- rotation
- Activity recreation
- UI mode changes

Do not restart a request because the screen rotated.

Protocol/network state should live outside individual Activity lifecycle instances.

---

# 51. Process Death

Where practical, preserve enough information to reconnect to active/recent server interactions after process recreation.

At minimum preserve:

- server profile
- conversation ID
- interaction ID
- last known sequence number

Then query the server and restore authoritative state.

---

# 52. Initial UI Priorities

Prioritize function and feedback over elaborate animation.

Good:

- Immediate state changes
- Fast scrolling
- Clear hierarchy
- Readable trace
- Smooth response streaming
- Stable layouts
- Obvious recording state

Avoid:

- Long animations that delay information
- Fake thinking animations
- Waiting to reveal progress
- Excessive visual effects during high-rate event streams

---

# 53. Suggested Implementation Phases

## Phase 1 - Project foundation

- Kotlin Android project
- `minSdk 26`
- Current target/compile SDK
- Basic settings
- Server URL
- Authentication
- REST client
- WebSocket client
- Protocol model layer
- Connection status

## Phase 2 - Text conversation

- List conversations
- Open conversation
- Submit typed interaction
- Receive events
- Stream final response
- Persist/recover interaction IDs
- Reconnect/replay

This phase proves the rich-client protocol before adding Android-specific complexity.

## Phase 3 - Live activity

- STT event rendering support
- Step/activity rendering
- Tool event rendering
- Agent event rendering
- Concurrent/nested execution support
- Context view
- Trace view
- Raw debug view

Even before local microphone support, the client should correctly visualize every server-side live event.

## Phase 4 - Attachments

- File picker
- Photo picker
- Camera
- Image preview
- Upload progress
- Share target
- Shared text/URL/image/file handling

## Phase 5 - Voice client

- Microphone capture
- Streaming audio
- Live STT
- Voice request lifecycle
- TTS reception/playback
- Audio focus
- Stop/replay

## Phase 6 - System assistant

- `VoiceInteractionService`
- `VoiceInteractionSessionService`
- `VoiceInteractionSession`
- Assistant role/setup flow
- Compact assistant presentation
- Voice interaction through system invocation
- Expand existing interaction into full app

## Phase 7 - Device capabilities

- Capability advertisement
- Capability dispatcher
- Device request/response
- Notifications
- Location
- Clipboard
- Intent launching
- Device information

Add sensitive capabilities incrementally rather than implementing all at once.

## Phase 8 - Screen context

- System-assistant assist context
- Supported screenshot/screen context handling
- Convert to generic Assister context
- User-visible indication of shared screen context

## Phase 9 - Tablet optimization

- Adaptive layout
- Multi-pane conversation/activity/context presentation
- Better landscape behavior

---

# 54. Recommended First Milestone

The first meaningful milestone should be:

> **Open the Android app, connect to Assister, send a typed message, and watch the complete interaction happen live.**

That means the first milestone already needs:

```text
Typed request
     |
interaction.started
     |
live routing/model/step/tool events
     |
response.delta
response.delta
response.delta
     |
interaction.completed
```

rendered in real time.

Do not wait until voice support to implement the live-feedback architecture.

It is foundational.

---

# 55. Second Milestone

The second major milestone should be:

> **Press the microphone button, speak, watch STT appear live, watch Assister execute live, see the final answer stream, then hear the answer.**

Expected UX:

```text
Microphone pressed
        |
Listening immediately visible
        |
Audio streams
        |
STT partials appear
        |
STT final appears
        |
Assister steps appear
        |
Tools/agents appear
        |
Final response streams
        |
TTS follows
```

---

# 56. System Assistant Milestone

A later milestone should be:

> **Select Assister as the Android default assistant and complete the same interaction using the system assistant invocation.**

The compact assistant UI should use the same:

- conversation
- interaction
- WebSocket
- event state
- audio
- context
- trace

components already proven in the full application.

Do not rebuild the interaction stack for system-assistant mode.

---

# 57. Performance / UX Targets

These are design targets, not guarantees against network/server latency.

### Local user action feedback

Aim to visually acknowledge local actions within roughly one UI frame whenever possible.

### Received protocol events

Render meaningful received events without intentional delay.

### STT

Display partial STT as soon as supplied by the server.

### Response text

Append response deltas immediately enough that generation visibly feels live.

### Scrolling

Streaming updates should not constantly steal scroll position if the user has manually scrolled upward.

Auto-follow only while the user remains near the bottom of the live conversation.

### Large trace streams

Virtualize/recycle long event lists.

Do not let a large trace make the conversation UI sluggish.

---

# 58. Important UI State Distinction

Do not represent everything as a chat message.

Maintain separate state for:

```text
User message
Assistant response
Live execution/activity
Context
Trace
Attachments
Audio state
Connection state
```

Some execution information may later collapse into a compact summary after completion.

During execution, it should remain visibly live.

---

# 59. Completed Interaction Presentation

While running:

```text
Assister

● Checking Home Assistant...
● Running history query...
● Analyzing result...

The office peaked at 78...
```

After completion, the UI can compact this to:

```text
Assister

The office peaked at 78.4°F around 3:20 PM.

[3 steps] [Context] [Trace]
```

The detailed execution remains inspectable.

This prevents historical conversations from becoming visually enormous while preserving rich live feedback.

---

# 60. Interaction Handoff Between Views

A user may begin from compact assistant mode and then tap **Expand**.

Expected behavior:

```text
VoiceInteractionSession
        |
interaction int_123
        |
tap Expand
        |
full Activity opens
        |
subscribes/restores int_123
        |
same interaction continues
```

Do not terminate/restart the interaction.

The same should apply if the user switches from portrait to landscape/tablet-style presentation.

---

# 61. Future Wake Word

Local wake-word support is a potential future feature.

It is not required for the initial Android project.

Do not make initial assistant functionality dependent on implementing a custom always-listening wake-word service.

First support:

- app microphone button
- Android system assistant invocation

Wake-word work can be evaluated separately with battery, Android background execution, privacy, and OEM behavior in mind.

---

# 62. What Should Remain Server-Side

Do not duplicate Assister intelligence in the Android application.

Keep these server-side:

- Model selection
- LLM execution
- Intent engine
- Agent execution
- Tool routing
- Home Assistant logic
- History calculations
- VLM inference
- Memory
- Conversation reasoning
- Context selection
- TTS generation unless a future protocol intentionally allows local TTS

The Android client should remain capable but thin.

Its intelligence is primarily:

- presentation
- audio/media handling
- Android integration
- protocol state
- device capabilities

---

# 63. What Belongs on Android

Android-specific functionality belongs in the Android project:

- Android Assistant role
- system assistant invocation
- microphone acquisition
- audio playback
- Android share intents
- file/photo picker
- camera capture
- notifications
- Android location API
- clipboard API
- launching intents/apps
- Android lifecycle management
- screen/assist context acquisition
- permission prompts
- local UI

Expose these capabilities to Assister through the common protocol instead of implementing server-side Android assumptions.

---

# 64. Testing Requirements

At minimum test:

## Android versions

- API 26 baseline
- A recent Android release
- Current development target

## Form factors

- Phone
- Tablet / large emulator

## Network

- Wi-Fi
- cellular-like network
- connection loss
- reconnect during active interaction
- server restart
- delayed WebSocket events

## Interaction

- text only
- voice
- attachment
- image
- cancellation
- tool failure
- TTS failure
- reconnect/replay
- concurrent agent/tool activity
- long streamed response

## Lifecycle

- rotate during request
- background app during request
- return to app
- destroy/recreate Activity
- process recreation where practical

---

# 65. Acceptance Criteria

## Foundation

- [ ] Application is primarily Kotlin
- [ ] `minSdk 26`
- [ ] Server URL is configurable
- [ ] Authentication is supported
- [ ] REST connection works
- [ ] WebSocket connection works
- [ ] Protocol code is isolated from UI code
- [ ] Current canonical protocol documentation is referenced during implementation

## Rich conversation

- [ ] Conversations can be opened
- [ ] Typed requests can be submitted
- [ ] Active interaction survives UI recreation
- [ ] Final messages persist via the server
- [ ] Retry/regenerate supported according to server protocol
- [ ] Running interactions can be cancelled

## Responsiveness

- [ ] Local user actions receive immediate visible feedback
- [ ] STT partials render live
- [ ] STT final text is visible
- [ ] User-facing Assister steps render live
- [ ] Model/routing status can be shown when supplied by server
- [ ] Tool activity renders live
- [ ] Agent activity renders live
- [ ] Concurrent activity is representable
- [ ] Response text streams live
- [ ] Text is not delayed waiting for TTS
- [ ] TTS status is visible
- [ ] Connection/reconnect status is visible

## Attachments

- [ ] Photo picker
- [ ] Camera capture
- [ ] File picker
- [ ] Image preview
- [ ] Attachment removal before submit
- [ ] Upload failure is visible
- [ ] Android share target
- [ ] Shared text/URL supported
- [ ] Shared image/file supported

## Voice

- [ ] Microphone input
- [ ] Streaming audio where protocol supports it
- [ ] Live transcription
- [ ] Cancel voice input
- [ ] TTS playback
- [ ] Stop/replay speech
- [ ] Audio focus handled correctly

## Context / trace

- [ ] Context used is viewable
- [ ] Context provenance is inspectable
- [ ] Live activity view exists
- [ ] Detailed trace exists
- [ ] Raw event/protocol view exists in developer mode
- [ ] Sensitive data is not indiscriminately written to Android logs

## Reconnect

- [ ] Last event sequence is tracked
- [ ] WebSocket reconnects
- [ ] Missed events replay
- [ ] Duplicate replay events do not duplicate UI state
- [ ] Network change does not create a second interaction
- [ ] Refresh/recreation does not lose active interaction

## System assistant

- [ ] App can qualify for Android Assistant role on supported devices
- [ ] `VoiceInteractionService` implemented
- [ ] `VoiceInteractionSessionService` implemented
- [ ] `VoiceInteractionSession` implemented
- [ ] Compact assistant UI
- [ ] Compact UI shows live STT/progress/response
- [ ] Compact interaction can expand into full application
- [ ] Expanding preserves the same interaction ID

## Capabilities

- [ ] Client advertises capabilities according to current protocol
- [ ] Device capability dispatcher exists
- [ ] Unsupported capabilities fail cleanly
- [ ] Permission-required capabilities fail cleanly
- [ ] User cancellation is representable
- [ ] Server receives structured device responses

## Large screens

- [ ] App remains usable on tablets
- [ ] Architecture supports multi-pane presentation
- [ ] Live progress remains visible without obscuring conversation

---

# 66. Development Rule for Codex

When implementing a feature that touches the Assister protocol:

1. Read the current canonical protocol document:
   https://github.com/GaryJS3/Assister/tree/main/docs/rich-client-protocol.md

2. Inspect the actual current server behavior if available.

3. Implement against the current protocol rather than examples remembered from earlier work.

4. If the Android requirement and current server protocol conflict, do not silently invent a private Android protocol.

5. Prefer extending the shared Assister protocol when the feature is generally useful to other rich clients.

6. Keep Android-only behavior local only when it is truly Android-specific.

---

# 67. Guiding Principle

The application should make Assister's work observable while it happens.

A successful interaction should feel like:

```text
I spoke
  ↓
I can see what it heard
  ↓
I can see where the request went
  ↓
I can see what model/engine is working
  ↓
I can see tools and agents execute
  ↓
I can see the answer being generated
  ↓
I can read the answer immediately
  ↓
I hear the answer when TTS is ready
  ↓
I can inspect the context and trace if I care
```

Not:

```text
I spoke
  ↓
spinner
  ↓
spinner
  ↓
spinner
  ↓
answer
```

That responsiveness and transparency should remain a defining characteristic of the Assister Android client.
