"""Reference implementation used to choose the app's models and tile overlaps.

The tiling here mirrors app/src/main/java/.../sr/TilePlanner.kt (a unit test pins
the Kotlin planner to spans produced by this file). Run with the LiteRT Python
runtime: pip install ai-edge-litert numpy pillow
"""
import io
import time

import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter

T, S = 128, 4  # model input tile, model scale


def tiles(n, core, pad):
    """Core spans along one axis: (start, length, window_start)."""
    out, x = [], 0
    while x < n:
        cw = min(core, n - x)
        wx = min(max(x - pad, 0), n - T) if n >= T else 0
        out.append((x, cw, wx))
        x += core
    return out


class Model:
    def __init__(self, path, threads=4):
        self.it = Interpreter(model_path=path, num_threads=threads)
        self.it.allocate_tensors()
        self.i = self.it.get_input_details()[0]['index']
        self.o = self.it.get_output_details()[0]['index']
        self.calls, self.secs = 0, 0.0

    def run(self, tile):
        self.it.set_tensor(self.i, tile[None].astype(np.float32))
        t = time.perf_counter()
        self.it.invoke()
        self.secs += time.perf_counter() - t
        self.calls += 1
        return self.it.get_tensor(self.o)[0]


def upscale(model, img, pad):
    """img: HxWx3 float32 in [0,1]. Clamp-to-edge outside the image, like the app."""
    h, w, _ = img.shape
    core = T - 2 * pad
    out = np.zeros((h * S, w * S, 3), np.float32)
    for (y, ch, wy) in tiles(h, core, pad):
        for (x, cw, wx) in tiles(w, core, pad):
            ys = np.clip(np.arange(wy, wy + T), 0, h - 1)
            xs = np.clip(np.arange(wx, wx + T), 0, w - 1)
            r = model.run(img[np.ix_(ys, xs)])
            oy, ox = (y - wy) * S, (x - wx) * S
            out[y * S:(y + ch) * S, x * S:(x + cw) * S] = r[oy:oy + ch * S, ox:ox + cw * S]
    return np.clip(out, 0, 1)


def y601(a):
    """RGB in [0,1] -> BT.601 luma in [16,235], the usual SR evaluation channel."""
    return 16 + 65.481 * a[..., 0] + 128.553 * a[..., 1] + 24.966 * a[..., 2]


def psnr(a, b):
    return 10 * np.log10(255.0 ** 2 / np.mean((a - b) ** 2))


def ssim(a, b):
    """Gaussian-window SSIM (11x11, sigma 1.5), as in Wang et al. 2004."""
    from numpy.lib.stride_tricks import sliding_window_view as swv
    g = np.exp(-((np.arange(11) - 5) ** 2) / (2 * 1.5 ** 2))
    g /= g.sum()
    k = np.outer(g, g)

    def f(x):
        return np.einsum('ijkl,kl->ij', swv(x, (11, 11)), k)

    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    ma, mb = f(a), f(b)
    sa, sb, sab = f(a * a) - ma ** 2, f(b * b) - mb ** 2, f(a * b) - ma * mb
    return float(np.mean(((2 * ma * mb + c1) * (2 * sab + c2)) / ((ma ** 2 + mb ** 2 + c1) * (sa + sb + c2))))


def load(p):
    return np.asarray(Image.open(p).convert('RGB'), np.float32) / 255.0


def jpeg(a, q):
    b = io.BytesIO()
    Image.fromarray((a * 255 + 0.5).astype(np.uint8)).save(b, 'JPEG', quality=q)
    return load(b)


def bicubic(a):
    im = Image.fromarray((a * 255 + 0.5).astype(np.uint8))
    return np.asarray(im.resize((a.shape[1] * S, a.shape[0] * S), Image.BICUBIC), np.float32) / 255.0
