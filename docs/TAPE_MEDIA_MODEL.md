# WalkTape magnetic-media model

WalkTape treats the player and the cassette as two physical systems. Production playback runs:

```text
decoded stereo float PCM
  → record EQ
  → magnetic coating (hysteresis, MOL/SOL, level-dependent HF loss)
  → replay EQ
  → particle/modulation noise and coating wander
  → selected machine (head, transport, response, electronics, mechanics)
  → AudioTrack
```

The media stage is allocation-free on the audio thread. Changing stock rebuilds the renderer at the audible playhead and discards already-rendered lookahead, so two formulations are never mixed in the output queue.

## Reference stocks

| Stock | Position | Replay EQ | Low-frequency headroom | 10 kHz headroom | Noise reference |
|---|---:|---:|---:|---:|---:|
| Sony CHF (1978) | IEC I | 120 µs | MOL400 3% +2.1 dB | SOL10k −1.4 dB | −50.7 dBA |
| TDK SA (1988) | IEC II | 70 µs | MOL315 +4.5 dB | measured SOL10k −3.6 dB | package bias noise −60.5 dB |
| TDK MA-X (1990 reference family) | IEC IV | 70 µs | MOL315 +6.0 dB | SOL10k +0.5 dB | bias noise −58.0 dB |

The public figures are stored separately from renderer calibration constants. Noise readings from different laboratories are not directly interchangeable because their reference flux, weighting, deck, track width, and bandwidth differ; the renderer therefore uses a documented full-band perceptual target instead of pretending those numbers are identical PCM RMS values.

## Behaviour represented

- 120 µs Type I and 70 µs Type II/IV record/replay paths
- asymmetric, memory-dependent magnetisation rather than a stateless clipper
- formulation-specific low-frequency MOL and high-frequency SOL behaviour
- hot-signal treble compression before broadband peak collapse
- particle hiss with stock-specific bandwidth and full-band target
- programme-dependent modulation noise
- smooth microscopic coating-density wander without synthetic clicks or theatrical drop-outs

Capstan wow/flutter, startup speed, head-gap/azimuth behaviour, crosstalk, motor/roller noise, measured response, and output electronics remain in the machine layer. The pre-existing TPS-L2 and HS-JX707 machine constants are unchanged.

## Sources and calibration boundary

- TDK SA 1988 package data: <https://tapeartrepros.sergiostuff.com/wp-content/uploads/2023/12/Packaging_TDK-SA-100_1988.pdf>
- TDK's original SA technical/J-card material: <https://tapeartrepros.sergiostuff.com/wp-content/uploads/2024/01/JCard_TDK-SA-C60_1974.pdf>
- Sony CHF comparative measurements: <https://audiochrome.blogspot.com/2020/07/cassette-tape-measurements-sony-hf-hf-s.html>
- TDK MA/MA-R comparative laboratory review: <https://www.worldradiohistory.com/UK/Hi-Fi-Choice/1983-1989/Hi-Fi%20Choice%20Iss.%20037%20Cassette%20Decks%20%26%20Tapes%201984.pdf>
- AES overview of slow-speed magnetic-recording headroom trade-offs: <https://secure.aes.org/forum/pubs/conventions/?elib=2820>

This is a physically structured reference model, not yet a bit-for-bit clone of one surviving cassette. A true physical-twin claim still requires calibrated record/playback sweeps, multi-level harmonic captures, and noise/modulation recordings from known samples on serviced reference decks.
