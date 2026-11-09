# Video Enhancer

An Android gallery that upscales photos and videos with AI models running entirely on the phone.
Nothing is uploaded. Originals are never modified; every result is a new file with its provenance
written into it.

Status: builds, passes unit tests and Android Lint. **Not yet run on a phone.** See
[docs/DEVICE_CHECKS.md](docs/DEVICE_CHECKS.md) for the ordered list of on-device checks.

## What it does

- **Gallery** of your photos and videos (MediaStore), with an "Enhanced" filter for results.
  Works with Android 14's "select photos" partial access.
- **Three models**, picked per job, all 4x with a 128 px input:
  - *QuickSRNet Large*, a fidelity model. Best PSNR of the candidates tested. Use it when the
    pixels matter.
  - *Real-ESRGAN General v3*, a compact GAN. Handles real-world noise and JPEG artifacts.
    Invents texture.
  - *Real-ESRGAN x4plus*, the full-size GAN. Best-looking, and roughly 11-16x slower per tile
    than v3 (11x in Qualcomm's NPU timings, 14-16x on the x86 CPU used for testing).
- **Output scales** 4x, 2x, or 1x ("restore": run at 4x, area-downsample back). 2x and 1x come
  from the 4x result.
- **Photos** become lossless PNGs in `Pictures/Video Enhancer`. They are written band by band, so
  output size is limited by storage, not memory.
- **Videos** become MP4 (HEVC, or AVC as fallback) in `Movies/Video Enhancer`. Every frame is
  upscaled; audio is copied untouched; timestamps and rotation are kept.
- **Compare**: slider and pinch-zoom against the original, plus a 1:1 pixel loupe that decodes
  real pixels from both files.
- **Lab**: per-device benchmark (median and p90 ms per tile), GPU-vs-CPU numerical deviation,
  accelerator and GPU precision, per-model tile overlap, video codec and quality.
- **No artificial limits**: no caps on resolution, video length or job time. Jobs run in a
  `specialUse` foreground service with a wake lock. On Android 15+ the `dataSync` and
  `mediaProcessing` types are capped at 6 h per 24 h in the background, and Android 16 applies
  job quotas to WorkManager even under a foreground service, so neither was used. `specialUse`
  needs a justification only for Play review, and this is a personal-use build.

What remains limited is hardware: a video job fails up front if no encoder on the phone accepts
the output size. The Detail screen checks this before you start and lists the encoders' limits.

## Honest expectations

- **GAN output is not evidence.** Real-ESRGAN models synthesise plausible texture. Every PNG
  carries a `Warning` text chunk saying so, and perceptual models are labelled in the UI. For
  anything you will measure or cite, use the fidelity model, and still treat its output as an
  estimate.
- **The fidelity model assumes clean input.** On JPEG q50 input it scored below plain bicubic
  ([docs/MODELS.md](docs/MODELS.md)). It suits clean, small images better than compressed ones.
- **Video is slow.** A 1080p frame is hundreds of tiles. The Lab benchmark gives your phone's
  tile time, and the Detail screen turns that into an estimate before you start.

## Build and install

Requirements: JDK 17+, Android SDK with platform 37 and build-tools 36 (AGP downloads missing
pieces if licenses are accepted). Network access on first build to fetch the models.

```
./gradlew :app:assembleDebug        # also downloads and SHA-256 checks the models (~68 MB)
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM unit tests
```

The model archives are cached in `.model-cache/` (git-ignored). To build offline, place the three
zips listed in `app/build.gradle.kts` there; the hash check still applies. A `release` build is
signed with the debug key so it can be sideloaded.

## CI

`.github/workflows/android.yml` runs on every push (except docs-only changes), on pull requests
and on demand (Actions tab, "Run workflow"). It runs the unit tests and Lint, builds debug and
release APKs, checks 16 KB page alignment, prints the signing certificate, and uploads the
**release APK** as an artifact named `video-enhancer-release-<run number>`, kept for 5 days.
Download it from the run's Summary page, unzip, and `adb install -r app-release.apk`.

Signing: Android only updates an app signed with the same key. CI reuses one debug keystore kept
in the Actions cache, which GitHub evicts after 7 days without use. For a permanent key, store one
as the repository secret `DEBUG_KEYSTORE_B64` (`base64 -w0 ~/.android/debug.keystore`); the
workflow prefers it when present. If the key ever changes, uninstall the app before installing.

## How it works

```
Photo:  ContentResolver -> BitmapRegionDecoder (band) -> EXIF remap -> sRGB ARGB
          -> TiledUpscaler (128 px windows, overlap cropped) -> LiteRT CompiledModel (GPU or CPU)
          -> optional box downscale -> PngStreamWriter (row stream) -> MediaStore (IS_PENDING)

Video:  MediaExtractor -> MediaCodec decoder (flexible YUV 4:2:0) -> RGB (stream's own matrix/range)
          -> TiledUpscaler -> RGB -> YUV straight into the encoder's input Image (BT.709 limited)
          -> MediaCodec encoder -> MediaMuxer (+ audio samples copied, interleaved by timestamp)
```

Design choices and the evidence behind them:

| Choice | Why |
|---|---|
| LiteRT 2.3.2 `CompiledModel` | In 2.x the GPU path is `CompiledModel` + `Accelerator.GPU`; `litert-gpu` ships only the native accelerator. GPU and CPU are compiled separately so the reported backend is the real one. |
| Per-model tile overlap | Measured two ways (difference from a wide-overlap reference, and a shifted-tiling seam test with controls): QuickSRNet 12 px and Real-ESRGAN v3 24 px leave no measurable seam; x4plus keeps a 2-5% residual at 32 px. Adjustable in Lab. |
| Region decode + streaming PNG | Output resolution is not capped by the Java heap. |
| EXIF orientation by pixel remap | Region decoders ignore EXIF. The remap is unit-tested for all eight orientations and cross-checked against Pillow. |
| sRGB decode | Inputs are converted to sRGB before upscaling. Wide-gamut (Display P3) colours are clipped. Recorded as a limitation. |
| YUV conversion in Kotlin | Uses the stream's signalled matrix and range (BT.601/709/2020, full/limited) and H.264/HEVC default chroma siting. Unit-tested against published BT.709 code values. |
| Timestamps copied from decoder | Variable-frame-rate video keeps its timing. |
| specialUse FGS, no WorkManager | See "No artificial limits" above. |
| 16 KB pages | LiteRT 2.3.2 `.so` files are 16 KB aligned (readelf), and `zipalign -P 16` passes on the APK. |

## Known gaps

- Not run on a device yet (no emulator with KVM on the build machine).
- HDR (PQ/HLG) and 10-bit video are refused with a message rather than tone-mapped.
- Wide-gamut photos are converted to sRGB; Ultra HDR gain maps are ignored (SDR base image only).
- Transparency is flattened onto white (the models are RGB-only).
- Real-ESRGAN x4plus can show faint tile seams in smooth areas (see docs/MODELS.md). Feathered
  blending or a larger-input export would fix it.
- Jobs live in memory; if Android kills the process, queued jobs are lost and the partial output
  may remain as a hidden, pending MediaStore entry.
- No NPU path. LiteRT 2.x supports NPUs through vendor plugins delivered separately; not wired up.

## Layout

```
app/src/main/java/io/github/slayer8366/enhancer/
  sr/       tiling, tensor packing, LiteRT engine, model registry, benchmark
  photo/    region/full decoders, EXIF orientation, streaming PNG writer, photo job
  video/    probe, encoder selection, YUV conversion, video job
  jobs/     queue, foreground service, settings, history (provenance records)
  media/    MediaStore queries, thumbnails, output files
  ui/       Compose screens: Gallery, Detail, Compare, Lab, Jobs
app/src/test/   JVM unit tests (tiling, packing, orientation, PNG, YUV)
tools/sr_reference/   Python reference used to pick models and overlaps
docs/       MODELS.md (evidence), DEVICE_CHECKS.md (what to verify on a phone)
```
