# 3DVR Companion / Cross-Device Control System

Open-source device control for humans and AI agents.

The immediate goal is simple: let a user authorize an agent to **inspect and operate the Android UI** on devices that may not have first-party agentic screen automation.

## Android UI agent

The Android app provides an `AccessibilityService` with primitives for:

- serialize the active UI hierarchy to compact JSON
- tap arbitrary coordinates
- swipe between coordinates
- click controls by visible text
- click controls by Android view/resource ID
- type into focused fields or fields selected by view ID
- scroll forward/backward
- invoke Back, Home, Recents, and Notifications

The setup activity opens Android Accessibility settings and can copy the active UI snapshot for debugging.

## Local Termux bridge

When the Accessibility service is enabled, Companion starts an authenticated command endpoint bound only to:

```text
127.0.0.1:8765
```

A random bearer token is generated per installation and stored privately by the app. The setup screen can copy both the token and a ready-to-paste Termux command.

Example command shape:

```bash
curl -s http://127.0.0.1:8765/command \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"command":"snapshot"}'
```

Supported commands currently include:

```text
snapshot
tap
swipe
clickText
clickId
typeFocused
typeId
scrollForward
scrollBackward
back
home
recents
notifications
```

### Safety boundary

The user must explicitly enable the Accessibility service in Android settings. The command server binds to the device loopback interface only; it is not reachable from Wi-Fi or cellular peers. Mutating commands require the per-install bearer token.

## Architecture direction

```text
AI / human command
      |
      v
Termux / local agent
      |
      v
authenticated 127.0.0.1 JSON bridge
      |
      v
3DVR Companion Android service
      |
      +--> UI hierarchy
      +--> tap / swipe
      +--> text entry
      +--> system navigation
```

Longer term this grows back into the original cross-device vision: phone, desktop, VR, and other open devices sharing input, displays, and agent control through a common protocol.

## Build

Current Android baseline:

- Android 16 / compileSdk 36
- Java 17
- Android Gradle Plugin 9.3
- Gradle 9.5

GitHub Actions builds the debug APK and uploads it as the `3dvr-companion-debug` workflow artifact.

## Next milestones

1. Install and exercise the APK on real Samsung hardware through Termux.
2. Add screenshot capture as a second perception channel when UI hierarchy is insufficient.
3. Build an agent loop: observe → plan → act → verify.
4. Add an explicit pairing flow for an outbound encrypted connection to a user-controlled agent/control plane.
5. Add action policy/confirmation gates for sensitive operations.
6. Test across older Samsung/Pixel hardware and document compatibility.

## License

GPL-3.0. See [LICENSE](LICENSE).
