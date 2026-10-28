# SRT Voice Converter (Android Kotlin MVP)

An Android Studio project that reads an `.srt` file, synthesizes each cue with the phone's configured Android Text-to-Speech engine, places audio at each cue's start timestamp, speeds up overlong speech to fit its subtitle slot, mixes overlapping cues, and saves WAV or MP3 through Android's document picker.

## Requirements
- Android Studio with JDK 17
- Android device/emulator running Android 8.0+ (API 26+)
- A working installed TTS engine (Google Speech Services can be selected in Android Settings → General management/System → Text-to-speech output)
- Internet is not requested by this app. Whether Google TTS itself needs network depends on the installed voice and whether its language data is downloaded.

## Open and build
1. Extract the ZIP.
2. Open the `SrtVoiceAndroid` folder in Android Studio.
3. Let Gradle sync. The MP3 dependency is `io.auxo.ame:ame-lite:0.1`; Gradle needs internet on first sync to fetch dependencies.
4. Run on your phone.
5. In Android settings, select/download the desired Google TTS voice.
6. Choose an `.srt`, then export WAV or MP3. Keep the app open during generation.

## Timing behavior
- Cues are placed at their original SRT start times.
- If a synthesized cue is longer than `end - start`, PCM resampling speeds it up to fit. This can raise pitch and sound unnatural at high speed.
- If a cue is shorter than its slot, the remainder is silence.
- Overlapping cues are mixed. Very loud overlap can clip.
- The app currently supports the common 16-bit PCM WAV output format from the installed TTS engine. If the selected engine writes another format, export reports an error.
- Export is limited to 60 minutes in this MVP to avoid allocating too much memory.

## MP3 note
MP3 is encoded locally from the generated WAV using AME-lite. This is a third-party dependency; if it fails to resolve/build in a future Android Studio setup, WAV export is still independent. Replace the encoder call in `MainActivity.kt` with a maintained MP3 encoder of your choice.

## Files
- `SrtParser.kt`: parses SRT cues and timestamps.
- `WavTools.kt`: reads 16-bit PCM WAV, fits audio to a time slot, writes WAV headers.
- `MainActivity.kt`: UI, file pickers, local TTS synthesis, timestamp mixing, export.
