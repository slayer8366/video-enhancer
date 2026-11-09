import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.slayer8366.enhancer"
    // AndroidX (core 1.19, Compose BOM 2026.09) requires compiling against API 37.
    // targetSdk stays 36 so no Android 17 runtime behavior changes are opted into.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.slayer8366.enhancer"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Phones are arm64; x86_64 keeps the emulator usable. LiteRT 2.3.2's arm64
        // .so files are 16 KB page aligned (checked with readelf: LOAD align 0x4000).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("String", "LITERT_VERSION", "\"${libs.versions.litert.get()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal-use build: sign release with the debug key so it can be sideloaded.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // CompiledModel.create(assets, path) maps the model; keep it stored uncompressed.
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// Model fetching. Weights are not committed to git. Each archive is pinned by
// SHA-256 and the build fails on any mismatch. Source: Qualcomm AI Hub Models
// v0.66.0 "universal" TFLite float exports (see docs/MODELS.md for provenance,
// licenses and the benchmark used to choose them).
// ---------------------------------------------------------------------------
val aiHub = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models"
val modelSpecs = listOf(
    // asset name | archive URL | archive SHA-256 | entry inside archive
    "quicksrnet_large.tflite|$aiHub/quicksrnetlarge/releases/v0.66.0/quicksrnetlarge-tflite-float.zip|" +
        "4a8e2005b53d39f066f64dc54c8f3fc3ab6ca429c3faf9f9886fae6060ca817f|quicksrnetlarge-tflite-float/quicksrnetlarge.tflite",
    "real_esrgan_general_x4v3.tflite|$aiHub/real_esrgan_general_x4v3/releases/v0.66.0/real_esrgan_general_x4v3-tflite-float.zip|" +
        "6b84752535739b5dd48ae777638f84e3927441b3b7f6deba9f5300eb8a4ce764|real_esrgan_general_x4v3-tflite-float/real_esrgan_general_x4v3.tflite",
    "real_esrgan_x4plus.tflite|$aiHub/real_esrgan_x4plus/releases/v0.66.0/real_esrgan_x4plus-tflite-float.zip|" +
        "7f6a9e2b564197af85b793a3824e9c51cd5bd19a8bf79e3f1b86dab982020700|real_esrgan_x4plus-tflite-float/real_esrgan_x4plus.tflite",
)

abstract class FetchModelsTask : DefaultTask() {
    @get:Input abstract val specs: ListProperty<String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        val out = outputDir.get().asFile.resolve("models").apply { deleteRecursively(); mkdirs() }
        for (line in specs.get()) {
            val (asset, url, sha256, entry) = line.split('|')
            val archive = cache.resolve(url.substringAfterLast('/'))
            if (!archive.isFile || sha256Of(archive) != sha256) {
                logger.lifecycle("Downloading $url")
                val tmp = cache.resolve(archive.name + ".part")
                URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val actual = sha256Of(tmp)
                if (actual != sha256) {
                    tmp.delete()
                    throw GradleException("SHA-256 mismatch for $url\n  expected $sha256\n  actual   $actual")
                }
                if (!tmp.renameTo(archive)) throw GradleException("Could not move $tmp to $archive")
            }
            var found = false
            ZipInputStream(archive.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    if (e.name == entry) {
                        out.resolve(asset).outputStream().use { zip.copyTo(it) }
                        found = true
                    }
                }
            }
            if (!found) throw GradleException("$entry not found in $archive")
        }
    }

    private fun sha256Of(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<FetchModelsTask>("fetchModels${variant.name.replaceFirstChar { it.uppercase() }}") {
            specs.set(modelSpecs)
            cacheDir.set(rootProject.layout.projectDirectory.dir(".model-cache"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, FetchModelsTask::outputDir)
    }
}
