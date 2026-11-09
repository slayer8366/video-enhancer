package io.github.slayer8366.enhancer.jobs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class MediaKind { PHOTO, VIDEO }

data class JobRequest(
    val id: Long,
    val source: Uri,
    val sourceName: String,
    val kind: MediaKind,
    val modelId: String,
    val outScale: Int,
)

enum class JobPhase { QUEUED, PREPARING, RUNNING, FINISHING, DONE, FAILED, CANCELLED }

data class JobState(
    val request: JobRequest,
    val phase: JobPhase = JobPhase.QUEUED,
    val backend: String? = null,
    val tilesDone: Long = 0,
    val tilesTotal: Long = 0,
    val framesDone: Long = 0,
    /** Estimated from duration x frame rate; container metadata can be off by a few frames. */
    val framesTotal: Long = 0,
    /** Exponential moving average of model time per tile. */
    val msPerTile: Double = 0.0,
    val startedAtMs: Long = 0,
    val finishedAtMs: Long = 0,
    val etaMs: Long? = null,
    val thermal: String? = null,
    val output: Uri? = null,
    val outputSize: String? = null,
    val error: String? = null,
    val notes: List<String> = emptyList(),
) {
    val active: Boolean get() = phase in setOf(JobPhase.QUEUED, JobPhase.PREPARING, JobPhase.RUNNING, JobPhase.FINISHING)

    /** Overall fraction done in [0,1]. Video progress counts whole frames plus the current frame's tiles. */
    val fraction: Float
        get() = when {
            phase == JobPhase.DONE -> 1f
            request.kind == MediaKind.VIDEO && framesTotal > 0 && tilesTotal > 0 ->
                ((framesDone + tilesDone.toDouble() / tilesTotal) / framesTotal).toFloat().coerceIn(0f, 1f)
            tilesTotal > 0 -> (tilesDone.toDouble() / tilesTotal).toFloat()
            else -> 0f
        }
}

/** In-process job queue shared by the UI and [EnhanceService]. */
object JobRepository {
    private val ids = AtomicLong(System.currentTimeMillis())
    private val state = MutableStateFlow<List<JobState>>(emptyList())
    val jobs: StateFlow<List<JobState>> = state.asStateFlow()

    @Volatile internal var running: Pair<Long, Job>? = null

    fun newId(): Long = ids.incrementAndGet()

    fun enqueue(context: Context, request: JobRequest) {
        state.update { it + JobState(request) }
        ContextCompat.startForegroundService(context, Intent(context, EnhanceService::class.java))
    }

    fun cancel(id: Long) {
        val r = running
        if (r != null && r.first == id) r.second.cancel()
        update(id) { if (it.phase == JobPhase.QUEUED) it.copy(phase = JobPhase.CANCELLED) else it }
    }

    fun clearFinished() = state.update { list -> list.filter { it.active } }

    internal fun nextQueued(): JobState? = state.value.firstOrNull { it.phase == JobPhase.QUEUED }

    internal fun update(id: Long, transform: (JobState) -> JobState) =
        state.update { list -> list.map { if (it.request.id == id) transform(it) else it } }

    fun get(id: Long): JobState? = state.value.firstOrNull { it.request.id == id }
}
