# 3DVR Companion / Cross-Device Control System

Open-source device control for humans and AI agents.

The immediate goal is simple: let a user authorize an agent to **inspect and operate the Android UI** on devices that may not have first-party agentic screen automation.

## Android UI Agent MVP

The Android app now provides an `AccessibilityService` with primitives for:

- serialize the active UI hierarchy to compact JSON
- tap arbitrary coordinates
- swipe between coordinates
- click controls by visible text
- click controls by Android view/resource ID
- type into focused fields or fields selected by view ID
- scroll forward/backward
- invoke Back, Home, Recents, and Notifications

The setup activity opens Android Accessibility settings and can copy the active UI snapshot for debugging.

### Safety boundary

The user must explicitly enable the accessibility service in Android settings. The MVP intentionally has **no remote network listener**. We first prove local inspection and interaction; authenticated agent transport comes next.

## Architecture direction

```text
AI / human command
      |
      v
Agent planner
      |
      v
authenticated command transport   <-- next milestone
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

Open the repository in a current Android Studio installation, install Android SDK 36, and build the `app` module.

## Next milestones

1. CI-build a debug APK.
2. Define a small versioned command protocol (`snapshot`, `tap`, `click`, `type`, `swipe`, `back`).
3. Add authenticated local/WebSocket transport with an explicit pairing flow.
4. Add screenshot capture as a second perception channel when UI hierarchy is insufficient.
5. Build an agent loop: observe → plan → act → verify.
6. Test on older Samsung/Pixel hardware and document compatibility.

## License

GPL-3.0. See [LICENSE](LICENSE).
