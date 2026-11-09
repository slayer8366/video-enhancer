"""PSNR/SSIM of each model on Set5/Set14 (4x), clean and JPEG-degraded input.

Usage: python -I bench.py MODELS_DIR DATA_DIR
  MODELS_DIR holds one folder per model with a .tflite inside (the extracted
             Qualcomm AI Hub zips, or the app's .model-cache archives unzipped).
  DATA_DIR   holds Set5/{HR,LR_x4}/... and Set14/{HR,LR_x4}/... from the
             Hugging Face datasets eugenesiow/Set5 and eugenesiow/Set14.
"""
import glob
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sr_ref import Model, S, bicubic, jpeg, load, psnr, ssim, upscale, y601  # noqa: E402

# Overlap used for the published table. Set5/Set14 LR images are small (most span one or two
# tiles per side), so few pixels sit near a seam. Per-model overlap needs: see seams.py.
PAD = 8


def evaluate(fn, pairs):
    ps, ss = [], []
    for lr, hr in pairs:
        sr = fn(lr)
        a, b = np.round(y601(sr)[S:-S, S:-S]), y601(hr)[S:-S, S:-S]
        ps.append(psnr(a, b))
        ss.append(ssim(a, b))
    return float(np.mean(ps)), float(np.mean(ss))


def main(mdir, ddir):
    models = {os.path.basename(p)[:-7]: p for p in glob.glob(mdir + '/*/*.tflite')}
    results = {}
    for ds in ('Set5', 'Set14'):
        pairs = []
        for hp in sorted(glob.glob(f'{ddir}/{ds}/HR/*/*.png')):
            lr = load(hp.replace('/HR/', '/LR_x4/').replace('_HR/', '_LR_x4/'))
            pairs.append((lr, load(hp)[:lr.shape[0] * S, :lr.shape[1] * S]))
        for cond, deg in (('clean', lambda a: a), ('jpeg_q50', lambda a: jpeg(a, 50))):
            dp = [(deg(lr), hr) for lr, hr in pairs]
            row = {'bicubic': evaluate(bicubic, dp)}
            for name, p in sorted(models.items()):
                m = Model(p)
                row[name] = evaluate(lambda a: upscale(m, a, PAD), dp) + (1000 * m.secs / m.calls,)
            results[f'{ds}/{cond}'] = row
    for k, row in results.items():
        print(f'\n## {k}  (PSNR-Y dB, SSIM-Y, ms per tile on this machine)')
        for name, v in row.items():
            print(f'  {name:28s} ' + '  '.join(f'{x:.4f}' if i == 1 else f'{x:.2f}' for i, x in enumerate(v)))
    with open('bench_results.json', 'w') as f:
        json.dump(results, f, indent=1)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
