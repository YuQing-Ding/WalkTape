# WalkTape

WalkTape is an Android cassette-listening experience built around the physical rituals of a tape: choosing a case from a shelf, opening it, unfolding the J-card, selecting a track, and placing the tape into a period player.

The current milestone rebuilds the original prototype's interface and playback transport. The first machine target is the 1979 Sony TPS-L2.

## Current experience

- A responsive cassette shelf with generated demo artwork and optional on-device albums
- A two-part open case with an acrylic J-card tray and animated cassette
- An unfolding J-card with liner notes, track list, lyrics, paper folds, and scrolling
- A track-selection drawer that rotates into a landscape playback scene
- A code-drawn TPS-L2-inspired player with moving reels, tape-pack progression, mechanical controls, HOT LINE ducking, and HIGH/LOW tone targets
- Local MediaStore album grouping and embedded artwork loading
- A stereo PCM playback engine for AAC/M4A, FLAC, MP3, WAV/PCM, Ogg/Vorbis, and Opus (subject to the device's installed codecs)
- A realtime TPS-L2 reference renderer with digitized LOW/HIGH response curves, fractional-delay wow/flutter, servo/cam transport behavior, measured-spectrum hiss, and gentle tape saturation

The old experimental effects remain in the repository for comparison, but playback no longer uses them. Every decoded source now passes through `TpsL2Dsp` as interleaved stereo float PCM.

## Build

Use Android Studio's bundled JDK (17 or newer) and Android SDK 34.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Visual regression renders

`WalkTapeViewRenderTest` renders the complete primary flow with Robolectric's native Android graphics backend. Its PNGs are written to:

```text
app/build/reports/walktape-renders/
```

## Audio engine

The implemented reference model and the remaining physical-unit calibration work are documented in [docs/TPS_L2_AUDIO_PLAN.md](docs/TPS_L2_AUDIO_PLAN.md). The public curve is now modeled, while the stricter “physical twin” badge remains reserved for repeatable capture matching across serviced units.

## License

MIT
