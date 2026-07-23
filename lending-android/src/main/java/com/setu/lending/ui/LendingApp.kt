package com.setu.lending.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.setu.lending.assistant.AssistantViewModel
import com.setu.lending.assistant.Phase

@Composable
fun LendingApp(vm: AssistantViewModel) {
    val context = LocalContext.current
    var overlayVisible by remember { mutableStateOf(true) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.setVoiceEnabled(granted) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        vm.setVoiceEnabled(granted)
        if (!granted) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        vm.startOnboarding()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            AppBar()
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Hero()
                Spacer(Modifier.size(14.dp))
                LoanForm(vm)
            }
        }

        if (overlayVisible) {
            AssistantOverlay(vm, onMinimize = { overlayVisible = false })
        } else {
            FloatingOrb(vm, onClick = { overlayVisible = true })
        }
    }
}

@Composable
private fun AppBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(BrandDark, Brand)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).background(Color(0x33FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("🌉", fontSize = 18.sp) }
        Spacer(Modifier.size(10.dp))
        Column {
            Text("Setu Finance", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Loans made simple", color = Color(0xCCFFFFFF), fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("RBI-registered NBFC · Demo", color = Color(0xB3FFFFFF), fontSize = 10.sp)
    }
}

@Composable
private fun Hero() {
    Column {
        Text("Apply for a loan in minutes", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Ink)
        Spacer(Modifier.size(4.dp))
        Text(
            "Just talk to Saathi — your voice assistant fills this form for you, checks eligibility and finds your best product.",
            color = Sub, fontSize = 13.sp
        )
    }
}

@Composable
private fun FloatingOrb(vm: AssistantViewModel, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(20.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            SiriOrb(phase = if (vm.phase == Phase.IDLE) Phase.THINKING else vm.phase,
                amplitude = vm.amplitude, modifier = Modifier.size(64.dp))
        }
    }
}
