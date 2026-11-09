package io.github.slayer8366.enhancer.sr

enum class ModelKind(val label: String) {
    /** Trained with a pixel loss (L1). Minimises error; adds little invented detail. */
    FIDELITY("Fidelity"),

    /** Trained with adversarial + perceptual losses. Sharp, but synthesises texture. */
    PERCEPTUAL("Perceptual"),
}

/**
 * A bundled model. All three share the same contract (checked from each export's
 * metadata.json and with the LiteRT interpreter): input [1,128,128,3] float32 RGB
 * in [0,1], output [1,512,512,3] in [0,1].
 *
 * [defaultPad] is the tile overlap chosen from the seam measurements in
 * docs/MODELS.md: QuickSRNet and General v3 use the smallest overlap with no
 * measurable seam; x4plus never fully converges and uses 32 px.
 */
data class ModelSpec(
    val id: String,
    val asset: String,
    val name: String,
    val kind: ModelKind,
    val params: String,
    val defaultPad: Int,
    val summary: String,
    val caveat: String,
    val source: String,
    val license: String,
    val tile: Int = 128,
    val scale: Int = 4,
) {
    val assetPath: String get() = "models/$asset"
}

object Models {
    val QUICKSRNET_LARGE = ModelSpec(
        id = "quicksrnet_large",
        asset = "quicksrnet_large.tflite",
        name = "QuickSRNet Large",
        kind = ModelKind.FIDELITY,
        params = "436K params",
        defaultPad = 12,
        summary = "Best PSNR of the candidates tested: 31.81 dB Set5, 27.33 dB Set14 at 4x " +
            "(bicubic: 28.42 / 25.70).",
        caveat = "Trained on clean bicubic downsampling. On JPEG-compressed input (q50) it scored " +
            "below plain bicubic because it sharpens compression artifacts.",
        source = "Qualcomm AI Hub Models v0.66.0 (QuickSRNet, arXiv:2303.04336)",
        license = "BSD-3-Clause (AIMET Model Zoo)",
    )

    val REAL_ESRGAN_GENERAL = ModelSpec(
        id = "real_esrgan_general_x4v3",
        asset = "real_esrgan_general_x4v3.tflite",
        name = "Real-ESRGAN General v3",
        kind = ModelKind.PERCEPTUAL,
        params = "1.21M params",
        defaultPad = 24,
        summary = "Compact GAN model trained on synthetic real-world degradations (blur, noise, JPEG). " +
            "Handles compressed photos and video frames.",
        caveat = "Invents plausible texture. PSNR on clean Set5 was 26.47 dB, below bicubic, " +
            "as expected for GAN models. Do not measure anything from its output.",
        source = "Qualcomm AI Hub Models v0.66.0 (Real-ESRGAN, arXiv:2107.10833)",
        license = "BSD-3-Clause",
    )

    val REAL_ESRGAN_X4PLUS = ModelSpec(
        id = "real_esrgan_x4plus",
        asset = "real_esrgan_x4plus.tflite",
        name = "Real-ESRGAN x4plus (Max)",
        kind = ModelKind.PERCEPTUAL,
        params = "16.7M params",
        defaultPad = 32,
        summary = "The full RRDB Real-ESRGAN. Highest perceptual quality, roughly 11x the compute " +
            "of General v3 per Qualcomm's own NPU timings.",
        caveat = "Same hallucination caveat as General v3. Its receptive field is wider than a 128 px tile, " +
            "so output depends slightly on tiling: at 32 px overlap, steps across tile boundaries " +
            "measured 2-5% above normal (faint seams possible in smooth areas).",
        source = "Qualcomm AI Hub Models v0.66.0 (Real-ESRGAN, arXiv:2107.10833)",
        license = "BSD-3-Clause",
    )

    val all = listOf(QUICKSRNET_LARGE, REAL_ESRGAN_GENERAL, REAL_ESRGAN_X4PLUS)

    fun byId(id: String): ModelSpec = all.firstOrNull { it.id == id } ?: QUICKSRNET_LARGE
}
