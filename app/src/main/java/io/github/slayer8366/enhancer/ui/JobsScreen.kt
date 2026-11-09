package io.github.slayer8366.enhancer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.slayer8366.enhancer.jobs.JobRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(vm: AppViewModel) {
    val jobs by JobRepository.jobs.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Jobs") },
            navigationIcon = { IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton(onClick = { JobRepository.clearFinished() }) { Text("Clear finished") } },
        )
    }) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize()) {
            items(jobs.reversed(), key = { it.request.id }) { s ->
                JobCard(s, onOpenOutput = s.output?.let { out ->
                    { vm.media.value.firstOrNull { it.uri.lastPathSegment == out.lastPathSegment }?.let { vm.push(Screen.Detail(it)) } }
                })
            }
        }
    }
}
