package io.github.slayer8366.enhancer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.slayer8366.enhancer.jobs.JobRepository
import io.github.slayer8366.enhancer.jobs.MediaKind

private enum class Access { NONE, PARTIAL, FULL }

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun access(context: Context): Access {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
        else -> Access.NONE
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var accessTick by remember { mutableIntStateOf(0) }
    val access = remember(accessTick) { access(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        accessTick++
        vm.refresh()
    }
    LaunchedEffect(access) { if (access != Access.NONE) vm.startObserving() }

    val media by vm.media.collectAsState()
    val filter by vm.filter.collectAsState()
    val jobs by JobRepository.jobs.collectAsState()
    val activeJobs = jobs.count { it.active }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video Enhancer") },
                actions = {
                    if (jobs.isNotEmpty()) {
                        TextButton(onClick = { vm.push(Screen.Jobs) }) {
                            Icon(Icons.AutoMirrored.Filled.List, null)
                            Text(if (activeJobs > 0) " $activeJobs running" else " Jobs")
                        }
                    }
                    IconButton(onClick = { vm.push(Screen.Lab) }) { Icon(Icons.Filled.Build, "Lab and settings") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (access == Access.NONE) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Allow access to your photos and videos to browse and enhance them. " +
                        "Everything runs on this phone; nothing is uploaded.")
                    Button(onClick = { launcher.launch(mediaPermissions()) }, Modifier.padding(top = 16.dp)) { Text("Allow access") }
                }
                return@Column
            }
            if (access == Access.PARTIAL) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Showing only the items you selected.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { launcher.launch(mediaPermissions()) }) { Text("Change") }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { vm.filter.value = f }, label = { Text(f.label) })
                }
            }
            val shown = when (filter) {
                Filter.ALL -> media
                Filter.PHOTOS -> media.filter { it.kind == MediaKind.PHOTO }
                Filter.VIDEOS -> media.filter { it.kind == MediaKind.VIDEO }
                Filter.ENHANCED -> media.filter { it.enhanced }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing here yet") }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(shown, key = { it.id }) { item ->
                        Thumbnail(item, Modifier.aspectRatio(1f).clickable { vm.push(Screen.Detail(item)) })
                    }
                }
            }
        }
    }
}
