package io.github.slayer8366.enhancer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.slayer8366.enhancer.ui.AppViewModel
import io.github.slayer8366.enhancer.ui.CompareScreen
import io.github.slayer8366.enhancer.ui.DetailScreen
import io.github.slayer8366.enhancer.ui.EnhancerTheme
import io.github.slayer8366.enhancer.ui.GalleryScreen
import io.github.slayer8366.enhancer.ui.JobsScreen
import io.github.slayer8366.enhancer.ui.LabScreen
import io.github.slayer8366.enhancer.ui.Screen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            EnhancerTheme {
                BackHandler(enabled = vm.stack.size > 1) { vm.pop() }
                when (val s = vm.stack.last()) {
                    Screen.Gallery -> GalleryScreen(vm)
                    is Screen.Detail -> DetailScreen(vm, s.item)
                    is Screen.Compare -> CompareScreen(vm, s.record)
                    Screen.Lab -> LabScreen(vm)
                    Screen.Jobs -> JobsScreen(vm)
                }
            }
        }
    }
}
