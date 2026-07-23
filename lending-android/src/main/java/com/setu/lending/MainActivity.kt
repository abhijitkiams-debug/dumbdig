package com.setu.lending

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.setu.lending.assistant.AssistantViewModel
import com.setu.lending.ui.LendingApp
import com.setu.lending.ui.SetuTheme

/**
 * Single-activity host. Launches straight into the lending app with the voice
 * assistant auto-starting on top of it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SetuTheme {
                val vm: AssistantViewModel = viewModel()
                LendingApp(vm)
            }
        }
    }
}
