"""How much tile overlap each model needs.

Upscales the same images with different overlaps and compares each result with a
wide-overlap reference. Differences are reported on the 0-255 scale.

Usage: python -I seams.py MODEL.tflite DATA_DIR [crop] [n_images]
Inputs are crops of the Set14 HR images. Only images at least `crop` px in both
dimensions are used, so every image spans several 128 px tiles (with crops smaller
than a tile, every overlap runs one tile and the comparison is meaningless).
"""
import glob
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sr_ref import Model, load, upscale  # noqa: E402


def main(model_path, ddir, crop=256, n_images=4, ref_pad=40):
    assert crop > 128, 'crop must exceed one tile'
    imgs = [im[:crop, :crop] for im in (load(p) for p in sorted(glob.glob(f'{ddir}/Set14/HR/*/*.png')))
            if min(im.shape[:2]) >= crop][:n_images]
    print(f'{len(imgs)} images, {crop}x{crop} crops, reference overlap {ref_pad} px')
    m = Model(model_path)
    refs = [upscale(m, im, ref_pad) for im in imgs]
    for pad in (0, 4, 8, 12, 16, 24, 32):
        if pad >= ref_pad:
            break
        d = np.concatenate([np.abs(upscale(m, im, pad) - r).ravel() for im, r in zip(imgs, refs)]) * 255
        cost = (128 / (128 - 2 * pad)) ** 2
        print(f'pad={pad:2d} cost x{cost:.2f}  mean|d|={d.mean():.4f}  p99.9={np.percentile(d, 99.9):.2f}  max={d.max():.1f}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], *(int(a) for a in sys.argv[3:5]))
