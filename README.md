# WalkTape

WalkTape is an Android cassette-listening experience built around the physical rituals of a tape: choosing a case from a shelf, opening it, unfolding the J-card, selecting a track, and placing the tape into a period player.

The current milestone rebuilds the original prototype's interface, playback transport, and analogue signal chain around selectable physical machines and magnetic tape stocks.

## Current experience

- A responsive cassette shelf built automatically and incrementally from the device library
- A two-part open case with an acrylic J-card tray and animated cassette
- An unfolding J-card with liner notes, track list, lyrics, paper folds, and scrolling
- A track-selection drawer that rotates into a landscape playback scene
- Code-drawn Sony TPS-L2, Sony WM-F2015, Sony WM-D6C, and Aiwa HS-JX707 players with moving reels, tape-pack progression, physical controls, and machine-specific behaviour
- Local MediaStore album grouping and embedded artwork loading
- A stereo PCM playback engine for AAC/M4A, ALAC/M4A, FLAC, MP3, WAV/PCM, Ogg/Vorbis, and Opus, including a bundled ALAC fallback
- A selectable machine DSP layer with independent TPS-L2, WM-F2015, WM-D6C, and HS-JX707 response, transport, head, electronics, and mechanical-noise models
- A true post-DSP WM-D6C five-segment peak meter, aligned to the audible playhead rather than decoder look-ahead
- Live Dolby OFF/B/C selectors on the WM-D6C and HS-JX707, backed by complementary sliding-band record/replay DSP rather than a tone-control preset
- A distraction-free machine view for every profile: tap the chassis to hide/show playback information and use the on-machine lock to prevent accidental toggles
- A separate magnetic-media layer for Sony CHF Type I, TDK SA Type II, and TDK MA-X Type IV, including 120/70 µs EQ, formulation-specific MOL/SOL, hysteretic saturation, programme-modulated particle noise, and coating wander

Every decoded source is converted to interleaved stereo float PCM. On supported machines the signal path is Dolby encode → `TapeMediumDsp` → Dolby decode → selected machine renderer; OFF bypasses both NR passes exactly. Machine, tape, Dolby mode, and healthy-unit condition can be switched live and persist across launches.

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

The machine references and remaining physical-unit calibration work are documented in [docs/TPS_L2_AUDIO_PLAN.md](docs/TPS_L2_AUDIO_PLAN.md), [docs/WM_F2015_AUDIO_PLAN.md](docs/WM_F2015_AUDIO_PLAN.md), and [docs/WM_D6C_AUDIO_PLAN.md](docs/WM_D6C_AUDIO_PLAN.md). The independent media and noise-reduction models are documented in [docs/TAPE_MEDIA_MODEL.md](docs/TAPE_MEDIA_MODEL.md) and [docs/DOLBY_BC_MODEL.md](docs/DOLBY_BC_MODEL.md). The stricter “physical twin” badge remains reserved for repeatable capture matching across serviced machines and known tape samples.

## License

MIT
