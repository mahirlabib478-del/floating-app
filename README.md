# FloatClip

FloatClip is an Android floating clipboard utility with a sequential Quick Text queue, separate A and C clipboard sheets, and a local TOTP authenticator.

## Features

- Floating overlay with five actions: Copy Next, Save to A, Save to C, Generate 2FA, and Close.
- Quick Text sheet supports add, edit, delete, reorder, and sequential copy.
- A and C sheets append each captured clipboard text as a new row. Existing rows are retained.
- Saved rows can be copied, edited, and deleted.
- Room database persists text locally.
- RFC 6238 TOTP generation uses the secret currently on the clipboard; the secret is not saved by FloatClip.
- No backend, analytics, or network permission is used by the app.

## Open and build

1. Install Android Studio with Android SDK Platform 35 and Java 17.
2. Clone this repository and open the project folder in Android Studio.
3. Let Gradle sync, then run the app on an Android device or emulator.
4. Tap **Start Floating Bubble** and grant the **Display over other apps** permission.
5. Return to FloatClip and tap **Start Floating Bubble** again.

The included Gradle launcher scripts bootstrap Gradle 8.10.2 if Gradle is not already installed. They download the Gradle distribution from the official Gradle distribution host on the first run. The scripts are intentionally lightweight and do not require the binary Gradle wrapper JAR.

## Using the bubble

- **1 · Copy Next:** Copies the next Quick Text entry. It loops to the first item after the last one.
- **2 · Save to A:** Appends the current clipboard text to A Sheet.
- **3 · Save to C:** Appends the current clipboard text to C Sheet.
- **4 · Generate 2FA:** Reads a Base32 TOTP secret from the clipboard, generates a six-digit code, and copies it.
- **5 · Close Bubble:** Stops the floating service. Start it again from the main app.

Quick Text order is based on a stable database ID and position. The last copied ID is remembered, so reordering or deleting entries does not leave the queue tied to a fragile list index.

## Security notes

- TOTP secrets are processed locally and are not persisted by the app.
- Treat copied 2FA secrets and generated codes as sensitive. Android may expose clipboard contents to the user and other apps.
- The overlay runs as a user-started foreground service on supported Android versions.
- Android versions and device manufacturers can vary in overlay and clipboard behavior; test on the target device before relying on it for authentication.

## CI

GitHub Actions builds the debug APK with Java 17, Android SDK Platform 35, and Gradle 8.10.2.
