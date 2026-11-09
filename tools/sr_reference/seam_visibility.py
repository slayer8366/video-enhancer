"""Are tile boundaries visible?

The same image is upscaled twice: tiling A, and tiling B shifted by half a tile core,
so A's boundaries fall in the middle of B's tiles. At A's boundary positions we compare
the pixel step (|difference between neighbours across the boundary|) in A and in B.
Same content, but only A has a boundary there, so:

  ratio = mean step in A / mean step in B   at A's boundaries

is 1.0 when tiling leaves no trace (exactly 1.0 when A and B are identical) and above
1.0 when there is a visible seam.

Comparisons use only the region both tilings share, minus a margin next to B's top/left
border (where only B sees an image edge).

Usage: python -I seam_visibility.py MODEL.tflite DATA_DIR PAD [PAD ...]
"""
import glob
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sr_ref import S, T, Model, load, upscale  # noqa: E402


def main(model_path, ddir, pads, crop=320, n_images=3, margin=32):
    imgs = [im[:crop, :crop] for im in (load(p) for p in sorted(glob.glob(f'{ddir}/Set14/HR/*/*.png')))
            if min(im.shape[:2]) >= crop][:n_images]
    m = Model(model_path)
    for pad in pads:
        core = T - 2 * pad
        d = core // 2
        ratios = []
        for im in imgs:
            # Common region in "B coordinates": A shifted by d, so both arrays hold the same content.
            a = upscale(m, im, pad)[d * S:, d * S:] * 255
            b = upscale(m, im[d:, d:], pad) * 255
            n = im.shape[0] - d
            lo = margin * S  # skip B's top/left border, where only B sees an image edge
            # A's tile boundaries (multiples of core in A) expressed in common coordinates.
            bounds = [(k * core - d) * S for k in range(1, im.shape[0] // core + 1) if margin <= k * core - d < n - 1]
            sa = [np.abs(a[lo:, p] - a[lo:, p - 1]).mean() for p in bounds] + \
                 [np.abs(a[p, lo:] - a[p - 1, lo:]).mean() for p in bounds]
            sb = [np.abs(b[lo:, p] - b[lo:, p - 1]).mean() for p in bounds] + \
                 [np.abs(b[p, lo:] - b[p - 1, lo:]).mean() for p in bounds]
            ratios.append(float(np.mean(sa) / np.mean(sb)))
        print(f'pad={pad:2d} core={core:3d} seam ratio per image: ' + ', '.join(f'{r:.3f}' for r in ratios), flush=True)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], [int(a) for a in sys.argv[3:]])
