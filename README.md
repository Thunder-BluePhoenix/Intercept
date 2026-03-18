# Intercept - Advanced Android Call Recorder

Intercept is a proof-of-concept Android application designed to reliably record both sides of a cellular phone call on modern Android devices (Android 10 through 14+), bypassing the severe background microphone restrictions imposed by the OS.

## 🚀 The Challenge of Modern Android Call Recording
Starting in Android 10, Google introduced strict **Concurrent Audio Capture** restrictions. When a cellular call becomes active, the Phone/Dialer app takes exclusive control of the microphone hardware and the `VOICE_COMMUNICATION` stream. Background apps attempting to record audio will silently capture a stream of absolute silence (0-byte or muted audio).

Additionally, Android's internal audio capture API (`AudioPlaybackCapture`) is strictly prohibited from capturing the `USAGE_VOICE_COMMUNICATION` stream, preventing direct digital capture of the remote caller's voice on stock AOSP devices.

## 💡 How Intercept Works
Intercept uses a multi-layered fallback architecture to bypass these restrictions:

1. **Accessibility Service Exemption**: By running a dummy Accessibility Service, Intercept elevates its audio capture privileges, preventing Android from forcefully muting the microphone when the dialer is active.
2. **Audio Source Prioritization**: Intercept targets the `VOICE_RECOGNITION` audio source, which is processed differently by the OS routing engine and often survives the call state lock.
3. **Telephony Downlink Hooking**: Intercept forcefully attempts to hook `VOICE_CALL` and `VOICE_DOWNLINK`. While stock AOSP blocks this, many OEM ROMs (Samsung One UI, Xiaomi MIUI, Oppo ColorOS) permit it, allowing direct, flawless digital capture of the remote party.
4. **Massive Mic Gain & Automatic Gain Control (AGC)**: If the device strictly blocks downlink capture, Intercept relies on acoustic bleed (the sound from the earpiece physically vibrating the microphone). To make this precise, Intercept applies a massive 4.5x digital gain multiplier to the captured stream, coupled with hardware Automatic Gain Control (AGC) if supported by the device chipset.

## 📦 Installation & Setup
1. Build the APK using Android Studio / Gradle (`.\gradlew assembleDebug`).
2. Install `intercept.apk` onto your device.
3. Open the app and tap **Start Recording**.
4. **CRITICAL**: The app will route you to your device's settings menu. You **must enable Intercept in Settings > Accessibility**. Without this, the app will be muted during phone calls.
5. (Optional but Recommended): During the call, turn on **Speakerphone**. This guarantees the microphone will pick up the remote party's voice perfectly if your phone's ROM blocks direct digital downlink.

## 🛠️ Tech Stack
- **Language**: Kotlin
- **Minimum SDK**: 29 (Android 10)
- **Target SDK**: 35 (Android 14+)
- **Audio Decoding**: Pure `AudioRecord` PCM 16-bit 16000Hz WAV mixing

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
