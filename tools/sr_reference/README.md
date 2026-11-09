# SR reference scripts

Python mirror of the app's tiling, used to choose models and tile overlaps. Results and method are
in [docs/MODELS.md](../../docs/MODELS.md).

- `sr_ref.py`: tiler, model runner and metrics shared by the scripts.
- `bench.py`: PSNR/SSIM on Set5/Set14, clean and JPEG q50 input.
- `seams.py`: tiled output versus a wide-overlap reference, for several overlaps.
- `seam_visibility.py`: shifted-tiling test of whether tile boundaries leave a visible step.

Run with `python -I` from any directory. `-I` keeps Python from importing stray files from the
current directory.
