package io.github.slayer8366.enhancer.ui

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.slayer8366.enhancer.jobs.HistoryRecord
import io.github.slayer8366.enhancer.media.MediaItem
import io.github.slayer8366.enhancer.media.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Gallery : Screen
    data class Detail(val item: MediaItem) : Screen
    data class Compare(val record: HistoryRecord) : Screen
    data object Lab : Screen
    data object Jobs : Screen
}

enum class Filter(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), ENHANCED("Enhanced") }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())
    val media: StateFlow<List<MediaItem>> = _media.asStateFlow()
    val filter = MutableStateFlow(Filter.ALL)
    val stack = mutableStateListOf<Screen>(Screen.Gallery)

    private var refreshJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh(debounceMs = 500)
    }
    private var observing = false

    fun startObserving() {
        if (observing) return
        observing = true
        getApplication<Application>().contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer,
        )
        refresh()
    }

    fun refresh(debounceMs: Long = 0) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            _media.value = withContext(Dispatchers.IO) {
                runCatching { MediaRepository.query(getApplication()) }.getOrDefault(emptyList())
            }
        }
    }

    fun push(s: Screen) = stack.add(s)
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false

    override fun onCleared() {
        if (observing) getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}
