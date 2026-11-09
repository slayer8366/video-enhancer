# Device checks

Nothing below has been run yet. The build machine has no Android device and no KVM for an
emulator, so everything here was verified only by compilation, unit tests, Android Lint and APK
inspection. Run the steps in order: each one is cheap and makes the next one meaningful.

Record the phone model, Android version and app version with every result.

| # | Step | Pass when | Evidence to keep |
|---|---|---|---|
| 1 | Install, open, allow access | Grid shows your photos and videos. With "Select photos" access, a banner says only selected items show. | Screenshot |
| 2 | Lab: read "This device" | The LiteRT line lists `GPU` among accelerators. | Screenshot |
| 3 | Lab: benchmark QuickSRNet Large | A line reading `GPU (FP32): median ... ms` plus a "vs CPU FP32" line. Max deviation of a few levels or less. | The two lines |
| 4 | Lab: switch to FP16, benchmark again | Faster median. Deviation still small. | Both results |
| 5 | Lab: benchmark the two Real-ESRGAN models | Both complete. x4plus is roughly 10x slower than v3. | Results |
| 6 | Small JPEG (around 640x480), Fidelity, 4x | Job finishes; PNG appears under Enhanced; Detail shows Provenance. | Output file |
| 7 | Compare on that output; tap an edge | Slider and zoom track both images; the loupe shows matching regions. | Screenshot |
| 8 | Portrait photo from a camera that writes EXIF rotation | Output is upright, same as the gallery shows the original. | Both files |
| 9 | Large photo, x4plus, 4x; turn screen off for 10 min | Progress keeps moving (wake lock); notification updates; Thermal shows a value. | Notification screenshots |
| 10 | Cancel that job | Job shows Cancelled; no partial PNG is left in Pictures/Video Enhancer. | File listing |
| 11 | 10 s 720p clip with audio, v3, 2x | MP4 plays; audio in sync; duration matches the original. | `ffprobe` of both files |
| 12 | Portrait video | Output plays upright (rotation kept as metadata). | `ffprobe` rotation tag |
| 13 | Variable-frame-rate clip (low-light phone recording) | Frame count and timestamps match the source. | `ffprobe -show_frames` counts |
| 14 | HDR clip (HDR10 or HLG) | Enhance is disabled or the job fails with the HDR message. Nothing is written. | Screenshot |
| 15 | 1080p clip at 4x | Detail screen says whether an encoder accepts 7680x4320 before you start. If yes, job runs; if no, the message lists encoder limits. | Screenshot |

Observations worth noting but not pass/fail: tile time over a long run (thermal throttling), GPU
vs CPU output deviation by model, and file sizes.

Useful commands:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s LiteRtEngine AudioCopier AndroidRuntime
ffprobe -v error -show_entries stream=codec_name,width,height,r_frame_rate,nb_frames:stream_tags=rotate -of compact FILE.mp4
```
