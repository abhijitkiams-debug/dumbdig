package com.setu.lending.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.setu.lending.assistant.AssistantViewModel
import com.setu.lending.assistant.Phase
import com.setu.lending.domain.Assessment
import com.setu.lending.domain.Products
import com.setu.lending.voice.Settings

/**
 * The voice assistant surface. It sits OVER the app as a bottom panel with a
 * dimmed scrim, showing the Siri orb, live captions, the latest exchange,
 * product suggestions, and a text fallback — without changing the form UI.
 */
@Composable
fun AssistantOverlay(vm: AssistantViewModel, onMinimize: () -> Unit) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000814))
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(
                Modifier
                    .imePadding()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {

                // header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Saathi", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.size(6.dp))
                    Text("· voice assistant", color = Sub, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Sub)
                    }
                    IconButton(onClick = { vm.restart() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Restart", tint = Sub)
                    }
                    IconButton(onClick = onMinimize) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimize", tint = Sub)
                    }
                }

                // orb
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    SiriOrb(phase = vm.phase, amplitude = vm.amplitude, modifier = Modifier.size(150.dp))
                }

                Text(
                    text = vm.caption.ifBlank { "Tap the mic and tell me what you need" },
                    color = Sub, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.size(10.dp))

                // scrollable content: latest exchange + product cards + result
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val lastBot = vm.messages.lastOrNull { !it.fromUser }
                    val lastUser = vm.messages.lastOrNull { it.fromUser }
                    lastUser?.let { Bubble(it.text, fromUser = true) }
                    lastBot?.let { Bubble(it.text, fromUser = false) }

                    if (vm.products.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Text("Suggested for you", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.size(6.dp))
                        vm.products.take(3).forEachIndexed { i, r -> ProductCard(vm, r, top = i == 0) }
                    }

                    if (vm.submitted) {
                        Spacer(Modifier.size(8.dp))
                        SuccessCard(vm.referenceId ?: "")
                    }

                    vm.error?.let {
                        Spacer(Modifier.size(6.dp))
                        Text(it, color = Color(0xFFB45309), fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.size(10.dp))

                // input row: text fallback + mic
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        placeholder = { Text("Type your answer…", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(6.dp))
                    IconButton(onClick = {
                        if (typed.isNotBlank()) { vm.onUserText(typed); typed = "" }
                    }) {
                        Icon(Icons.Filled.Send, contentDescription = "Send", tint = Brand)
                    }
                    MicButton(vm)
                }

                if (!Settings.hasApiKey(context)) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Voice is off — add your Sarvam API key in Settings ⚙ to talk. You can still type.",
                        color = Sub, fontSize = 11.sp
                    )
                }
            }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
}

@Composable
private fun MicButton(vm: AssistantViewModel) {
    val listening = vm.phase == Phase.LISTENING
    val speakingOrThinking = vm.phase == Phase.SPEAKING || vm.phase == Phase.THINKING
    Box(
        Modifier
            .size(52.dp)
            .background(
                brush = Brush.linearGradient(listOf(Accent1, Accent2)),
                shape = CircleShape
            )
            .clickable {
                when {
                    listening -> vm.stopListening()
                    speakingOrThinking -> vm.stopTurn()
                    else -> vm.listenAgain()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when {
                listening || speakingOrThinking -> Icons.Filled.Stop
                else -> Icons.Filled.Mic
            },
            contentDescription = "Microphone",
            tint = Color.White
        )
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .heightIn(min = 0.dp)
                .background(
                    color = if (fromUser) Accent1 else Color(0xFFF1F5F9),
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text, color = if (fromUser) Color.White else Ink, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ProductCard(vm: AssistantViewModel, r: Assessment, top: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        border = if (top) androidx.compose.foundation.BorderStroke(1.dp, Accent1) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.product.emoji}  ${r.product.name}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (top) {
                    Spacer(Modifier.size(6.dp))
                    Box(Modifier.background(Accent1, RoundedCornerShape(20.dp)).padding(horizontal = 7.dp, vertical = 1.dp)) {
                        Text("Top pick", color = Color.White, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(r.product.blurb, color = Sub, fontSize = 11.sp)
            Spacer(Modifier.size(6.dp))
            Text(
                "${"%.2f".format(r.rate)}% p.a. • up to ${Products.inrShort(r.maxEligible)} • EMI ${Products.inr(r.emi)}/mo",
                fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = {
                vm.setField("loanType", r.product.id)
                if ((vm.values["amount"] ?: "").isBlank()) vm.setField("amount", r.maxEligible.toString())
            }) { Text("Apply for ${r.product.name}") }
        }
    }
}

@Composable
private fun SuccessCard(reference: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("🎉 Application submitted", fontWeight = FontWeight.Bold, color = BrandDark)
            Text("Reference $reference — a credit officer will call you shortly.", color = Sub, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(Settings.apiKey(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sarvam voice settings") },
        text = {
            Column {
                Text("Paste your Sarvam API subscription key to enable speech. It's stored only on this device.", fontSize = 13.sp, color = Sub)
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("SARVAM_API_KEY") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { Settings.setApiKey(context, key); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
