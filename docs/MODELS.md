# Models: provenance, evidence and limits

This page records why the app ships the three models it does, what was measured, how, and what was
not measured. Measurements were made on 2026-11-09 with the scripts in `tools/sr_reference/`.

## What ships

| In the app | Kind | Params | Default tile overlap | Upstream | License |
|---|---|---|---|---|---|
| QuickSRNet Large | Fidelity (L1-trained) | 436K | 12 px | QuickSRNet, arXiv:2303.04336 | BSD-3-Clause (AIMET Model Zoo) |
| Real-ESRGAN General v3 | Perceptual (GAN) | 1.21M | 24 px | Real-ESRGAN, arXiv:2107.10833 | BSD-3-Clause |
| Real-ESRGAN x4plus ("Max") | Perceptual (GAN) | 16.7M | 24 px | Real-ESRGAN, arXiv:2107.10833 | BSD-3-Clause |

All three files are the "universal" TFLite float32 exports from Qualcomm AI Hub Models v0.66.0
(Hugging Face `qualcomm/QuickSRNetLarge`, `qualcomm/Real-ESRGAN-General-x4v3`,
`qualcomm/Real-ESRGAN-x4plus`). Parameter counts and licenses are from those model cards. The
build downloads the archives and refuses to continue unless each SHA-256 matches the value pinned
in `app/build.gradle.kts`. Weights are not committed to git.

**I/O contract** (read from each export's `metadata.json` and confirmed with the LiteRT
interpreter): input `[1,128,128,3]` float32 RGB in [0,1], output `[1,512,512,3]` float32 in [0,1].
Pixel shuffle is expressed as 4-D RESHAPE/TRANSPOSE ops, which the LiteRT GPU backend supports.
That last point is an inference from operator support, not a test on a phone.

## Why these three

Candidates were the six float exports Qualcomm publishes for 4x SR at a 128 px input. Each was run
over Set5 and Set14 through the same tiler the app uses (8 px overlap).

Protocol: Set5/Set14 LR images as distributed in the Hugging Face datasets `eugenesiow/Set5` and
`eugenesiow/Set14`; output rounded to 8 bits; PSNR and SSIM on BT.601 luma; 4 px border crop. As a
check on the protocol, plain bicubic gives 28.42 dB on Set5, which matches the figure usually
published for bicubic 4x.

### Clean input

| Model | Set5 PSNR / SSIM | Set14 PSNR / SSIM | ms per tile* |
|---|---|---|---|
| bicubic | 28.42 / 0.8106 | 25.70 / 0.6905 | |
| **QuickSRNet Large** | **31.81 / 0.8909** | **27.33 / 0.7494** | 36.9 |
| QuickSRNet Medium | 31.33 / 0.8823 | 27.14 / 0.7427 | 6.8 |
| XLSR | 30.97 / 0.8749 | 26.98 / 0.7381 | 5.6 |
| SESR-M5 | 30.91 / 0.8738 | 26.93 / 0.7374 | 32.5 |
| Real-ESRGAN General v3 | 26.47 / 0.8235 | 24.37 / 0.6927 | 117.4 |

### JPEG-compressed input (LR saved at quality 50, then upscaled)

| Model | Set5 PSNR / SSIM | Set14 PSNR / SSIM |
|---|---|---|
| bicubic | 26.64 / 0.7264 | 24.63 / 0.6213 |
| QuickSRNet Large | 26.40 / 0.7132 | 24.42 / 0.6127 |
| QuickSRNet Medium | 26.55 / 0.7177 | 24.50 / 0.6151 |
| XLSR | 26.54 / 0.7157 | 24.49 / 0.6135 |
| SESR-M5 | 26.58 / 0.7173 | 24.50 / 0.6142 |
| Real-ESRGAN General v3 | 24.60 / **0.7527** | 23.08 / **0.6274** |

\* Timing column: one x86 cloud VM, 4 threads, XNNPACK. Useful only for relative cost between
models. Phone numbers come from the in-app Lab benchmark.

### What the numbers say, and what they don't

- **QuickSRNet Large is the fidelity model** because it had the highest PSNR and SSIM of the
  candidates on clean input, by 0.5 dB or more on Set5.
- **Fidelity models only win on clean input.** All four were trained on clean bicubic
  downsampling. Given JPEG-compressed input they scored *below plain bicubic*, because they
  sharpen block artifacts as if those were detail. Quality 50 is harsher than typical camera
  JPEGs, so this test shows the direction of the effect, not its size on your photos.
- **Real-ESRGAN's low PSNR is expected, not a defect.** GAN-trained models trade distortion for
  realism (Blau and Michaeli, "The Perception-Distortion Tradeoff", CVPR 2018). PSNR and SSIM do
  not measure perceived sharpness. No perceptual metric (LPIPS, NIQE, user study) was run here.
- **Real-ESRGAN invents texture.** Its output must not be used to measure, count or identify
  anything. The app writes this warning into each output PNG and labels the model in the UI.
- Set5 + Set14 is 19 images. These are small, old benchmarks. Treat differences under about
  0.1 dB as noise.

## Tile overlap (seams)

The models take a fixed 128x128 input, so large images are processed in tiles. Each tile carries
extra context ("overlap") that is cropped away. Too little overlap leaves visible seams.

Method (`seams.py`): upscale 256x256 crops of Set14 HR images with different overlaps and compare
each result with a 40 px-overlap reference. Every crop spans at least 3x3 tiles. Differences are
on the 0-255 scale. Rule for the default: the smallest overlap whose maximum difference is at most
one 8-bit level.

| Model | Overlap | Compute cost | Mean diff | 99.9th pct | Max |
|---|---|---|---|---|---|
| QuickSRNet Large | 0 px | 1.00x | 0.1318 | 14.27 | 120.3 |
| QuickSRNet Large | 8 px | 1.31x | 0.0006 | 0.09 | 1.0 |
| QuickSRNet Large | **12 px** | 1.51x | 0 | 0 | 0 |
| Real-ESRGAN v3 | 0 px | 1.00x | 0.5306 | 29.24 | 127.7 |
| Real-ESRGAN v3 | 16 px | 1.78x | 0.0267 | 1.81 | 7.3 |
| Real-ESRGAN v3 | **24 px** | 2.56x | 0.0015 | 0.15 | 1.0 |
| Real-ESRGAN v3 | 32 px | 4.00x | 0 | 0 | 0 |
| Real-ESRGAN x4plus | 24 px | 2.56x | pending | pending | pending |

x4plus re-measurement with the corrected method is pending (see correction note below).
QuickSRNet and v3: 4 crops. Zero difference at 12 px (QuickSRNet) and 32 px (v3) means the
tiled result is identical to the reference, which is what you expect once the overlap covers the
network's receptive field.

**Correction.** A first pass used Set14 *LR* images as input. Most of those are 128 px or
smaller, so they ran as a single tile and contributed zeros, and only one image actually had
seams. That pass reported "exact" at 8 px (QuickSRNet) and 16 px (v3) and underestimated the
x4plus means. The table above replaces it, and the defaults in the app were raised to match.

The Kotlin tiler is unit-tested against the Python reference (identical spans), and against a
fake model with a known receptive field: with enough overlap, tiled output equals whole-image
output exactly. With zero overlap the same test fails, so it can tell the two apart.

## Speed: what is known

Qualcomm publishes per-tile times for these exports, for example on Snapdragon 8 Elite (TFLite,
float): QuickSRNet Large 1.10 ms, Real-ESRGAN v3 3.27 ms, Real-ESRGAN x4plus 36.7 ms. **Those run
on the Hexagon NPU through Qualcomm's own runtime.** This app uses LiteRT's GPU or CPU backend, so
expect slower tiles. The ratio between models is the useful part (x4plus is about 11x v3).

Tile counts grow with area. A 1080p frame at 16 px overlap is 20 x 12 = 240 tiles. At an assumed
20 ms per tile, that is 4.8 s per frame, or about 2.4 hours per minute of 30 fps video. The
20 ms figure is an illustration, not a measurement. Run the Lab benchmark to get your phone's
numbers, and the Detail screen will estimate from them.

## Reproducing

```
pip install ai-edge-litert numpy pillow
python -I tools/sr_reference/bench.py MODELS_DIR DATA_DIR
python -I tools/sr_reference/seams.py MODEL.tflite DATA_DIR 224
```

`MODELS_DIR` holds one folder per extracted model archive (see `.model-cache/` after a build).
`DATA_DIR` holds the extracted `Set5/` and `Set14/` HR and LR_x4 folders from the Hugging Face
datasets above. Those datasets are not redistributed here.
