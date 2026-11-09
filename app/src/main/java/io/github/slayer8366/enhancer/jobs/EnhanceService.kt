package io.github.slayer8366.enhancer.jobs

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.slayer8366.enhancer.MainActivity
import io.github.slayer8366.enhancer.R
import io.github.slayer8366.enhancer.photo.PhotoEnhancer
import io.github.slayer8366.enhancer.sr.LiteRtEngine
import io.github.slayer8366.enhancer.sr.Models
import io.github.slayer8366.enhancer.sr.TileProgress
import io.github.slayer8366.enhancer.video.VideoEnhancer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Runs queued jobs one at a time on a single dedicated thread (LiteRT models are
 * created and used on the same thread). Holds a partial wake lock while working,
 * so long jobs keep going with the screen off. See the manifest for why the
 * service type is specialUse.
 */
class EnhanceService : Service() {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sr-worker").apply { priority = Thread.NORM_PRIORITY } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    // `loop` and `lastStartId` are only touched on the main thread, which serialises
    // onStartCommand with the end-of-queue check in drain().
    private var loop: kotlinx.coroutines.Job? = null
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            JobRepository.cancel(intent.getLongExtra(EXTRA_ID, -1))
            // A stale notification action must not restart a foreground service from the background.
            if (loop == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        goForeground(buildNotification(null))
        if (loop == null) loop = scope.launch { drain() }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        acquireWakeLock()
        try {
            while (true) {
                val next = JobRepository.nextQueued() ?: break
                val job = scope.launch { run(next) }
                JobRepository.running = next.request.id to job
                job.join()
                JobRepository.running = null
            }
        } finally {
            releaseWakeLock()
            withContext(NonCancellable + Dispatchers.Main) {
                loop = null
                if (JobRepository.nextQueued() != null) {
                    // A job arrived while this loop was winding down.
                    loop = scope.launch { drain() }
                } else {
                    ServiceCompat.stopForeground(this@EnhanceService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    // Ignored if a newer start arrived; that start will find loop == null and relaunch.
                    stopSelf(lastStartId)
                }
            }
        }
    }

    private suspend fun run(state: JobState) {
        val req = state.request
        val id = req.id
        val spec = Models.byId(req.modelId)
        val settings = SettingsStore.settings.value
        val pad = settings.overlapFor(spec)
        val t0 = SystemClock.elapsedRealtime()
        JobRepository.update(id) { it.copy(phase = JobPhase.PREPARING, startedAtMs = System.currentTimeMillis()) }
        try {
            LiteRtEngine.open(this, spec, settings.accelerator, settings.gpuPrecision).use { engine ->
                JobRepository.update(id) { it.copy(backend = engine.backendLabel, phase = JobPhase.RUNNING) }
                var ema = 0.0
                var lastUpdate = 0L
                fun onTile(p: TileProgress, frame: Long?) {
                    val ms = p.lastTileNanos / 1e6
                    ema = if (ema == 0.0) ms else ema * 0.95 + ms * 0.05
                    // Small models can finish hundreds of tiles per second; publish state at most ~10 Hz.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate < 100 && p.done != p.total) return
                    lastUpdate = now
                    JobRepository.update(id) { s ->
                        val remainingTiles = if (frame != null && s.framesTotal > 0) {
                            (s.framesTotal - frame) * p.total - p.done
                        } else p.total - p.done
                        s.copy(
                            tilesDone = p.done, tilesTotal = p.total, framesDone = frame ?: s.framesDone,
                            msPerTile = ema, etaMs = (remainingTiles.coerceAtLeast(0) * ema).toLong(),
                        )
                    }
                    maybeNotify()
                }

                val result: HistoryRecord = when (req.kind) {
                    MediaKind.PHOTO -> {
                        val mime = contentResolver.getType(req.source)
                        val r = PhotoEnhancer(this).enhance(
                            req.source, req.sourceName, mime, spec, engine, pad, req.outScale,
                            onStart = { w, h, tiles ->
                                JobRepository.update(id) { it.copy(tilesTotal = tiles, outputSize = "${w}x$h") }
                            },
                            onTile = { onTile(it, null) },
                        )
                        record(req, spec.id, pad, engine.backendLabel, r.uri, r.width, r.height,
                            JobRepository.get(id)?.tilesTotal ?: 0, ema, t0, r.notes)
                    }
                    MediaKind.VIDEO -> {
                        val r = VideoEnhancer(this).enhance(
                            req.source, req.sourceName, spec, engine, pad, req.outScale,
                            settings.videoCodec, settings.videoQuality,
                            onStart = { info, w, h, _ ->
                                JobRepository.update(id) {
                                    it.copy(framesTotal = info.estimatedFrames, outputSize = "${w}x$h")
                                }
                            },
                            onProgress = { onTile(it.tile, it.frame) },
                        )
                        JobRepository.update(id) { it.copy(phase = JobPhase.FINISHING, framesDone = r.frames) }
                        record(req, spec.id, pad, engine.backendLabel, r.uri, r.width, r.height,
                            (JobRepository.get(id)?.tilesTotal ?: 0) * r.frames, ema, t0, r.notes)
                    }
                }
                History.add(this, result)
                JobRepository.update(id) {
                    it.copy(
                        phase = JobPhase.DONE, output = result.output, notes = result.notes,
                        finishedAtMs = System.currentTimeMillis(), etaMs = 0,
                    )
                }
            }
        } catch (e: CancellationException) {
            JobRepository.update(id) { it.copy(phase = JobPhase.CANCELLED, finishedAtMs = System.currentTimeMillis()) }
        } catch (t: Throwable) {
            JobRepository.update(id) {
                it.copy(phase = JobPhase.FAILED, error = t.message ?: t.toString(), finishedAtMs = System.currentTimeMillis())
            }
        }
        maybeNotify(force = true)
    }

    private fun record(
        req: JobRequest, modelId: String, pad: Int, backend: String, out: android.net.Uri,
        w: Int, h: Int, tiles: Long, ms: Double, t0: Long, notes: List<String>,
    ) = HistoryRecord(
        output = out, source = req.source, sourceName = req.sourceName, kind = req.kind, modelId = modelId,
        outScale = req.outScale, pad = pad, backend = backend, outWidth = w, outHeight = h, tiles = tiles,
        msPerTile = ms, totalMs = SystemClock.elapsedRealtime() - t0, finishedAt = System.currentTimeMillis(),
        notes = notes,
    )

    private fun maybeNotify(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        val running = JobRepository.jobs.value.firstOrNull { it.phase == JobPhase.RUNNING || it.phase == JobPhase.FINISHING }
        running?.let { s -> JobRepository.update(s.request.id) { it.copy(thermal = thermalLabel()) } }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(running))
    }

    private fun thermalLabel(): String = when (getSystemService(PowerManager::class.java).currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun buildNotification(s: JobState?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_jobs), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val queued = JobRepository.jobs.value.count { it.phase == JobPhase.QUEUED }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(s != null)
            .setOnlyAlertOnce(true)
            .setContentTitle(s?.let { "Enhancing ${it.request.sourceName}" } ?: "Preparing")
        if (s != null) {
            val pct = (s.fraction * 1000).toInt()
            b.setProgress(1000, pct, s.tilesTotal == 0L)
            b.setContentText(
                buildString {
                    append("%.1f%%".format(s.fraction * 100))
                    s.etaMs?.let { append(" · ").append(formatDuration(it)).append(" left") }
                    if (queued > 0) append(" · $queued queued")
                },
            )
            val cancel = PendingIntent.getService(
                this, s.request.id.toInt(),
                Intent(this, EnhanceService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, s.request.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Cancel", cancel)
        }
        return b.build()
    }

    @SuppressLint("WakelockTimeout") // Deliberate: jobs can legitimately run for many hours. Released in drain().
    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoEnhancer:job")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        executor.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jobs"
        private const val NOTIFICATION_ID = 1
        const val ACTION_CANCEL = "cancel"
        const val EXTRA_ID = "id"

        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return when {
                s >= 86_400 -> "%dd %dh".format(s / 86_400, (s % 86_400) / 3600)
                s >= 3600 -> "%dh %dm".format(s / 3600, (s % 3600) / 60)
                s >= 60 -> "%dm %ds".format(s / 60, s % 60)
                else -> "${s}s"
            }
        }
    }
}

