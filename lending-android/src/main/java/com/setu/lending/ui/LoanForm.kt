package com.setu.lending.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.setu.lending.assistant.AssistantViewModel
import com.setu.lending.domain.Choice
import com.setu.lending.domain.FieldType
import com.setu.lending.domain.FormField
import com.setu.lending.domain.FormSchema

@Composable
fun LoanForm(vm: AssistantViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        CompletionHeader(vm)
        Spacer(Modifier.size(12.dp))
        for (group in FormSchema.groups) {
            if (group == "Consent") continue
            GroupCard(vm, group)
            Spacer(Modifier.size(12.dp))
        }
        ConsentCard(vm)
        Spacer(Modifier.size(80.dp)) // room above the assistant bar
    }
}

@Composable
private fun CompletionHeader(vm: AssistantViewModel) {
    val c = vm.completion
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Application progress", fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.size(8.dp))
            LinearProgressIndicator(
                progress = { c.pct / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Brand,
                trackColor = Line
            )
            Spacer(Modifier.size(6.dp))
            Text("${c.done} of ${c.required} required details • ${c.pct}%", color = Sub, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GroupCard(vm: AssistantViewModel, group: String) {
    val fields = FormSchema.FIELDS.filter { it.group == group }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(group, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.size(10.dp))
            for (f in fields) {
                FieldRow(vm, f)
                Spacer(Modifier.size(10.dp))
            }
        }
    }
}

@Composable
private fun FieldRow(vm: AssistantViewModel, f: FormField) {
    val value = vm.values[f.id] ?: ""
    val label = if (f.required) "${f.label} *" else f.label
    when (f.type) {
        FieldType.CHOICE -> ChoiceField(label, value, f.choices) { vm.setField(f.id, it) }
        FieldType.NUMBER -> OutlinedTextField(
            value = value,
            onValueChange = { vm.setField(f.id, it.filter { ch -> ch.isDigit() || ch == '.' }) },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        else -> OutlinedTextField(
            value = value,
            onValueChange = { vm.setField(f.id, it) },
            label = { Text(label) },
            placeholder = { f.hint?.let { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ChoiceField(label: String, value: String, choices: List<Choice>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.value == value }?.label
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Soft, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(label, color = Sub, fontSize = 11.sp)
            Spacer(Modifier.size(2.dp))
            Text(
                selectedLabel ?: "Tap to select…",
                color = if (selectedLabel == null) Sub else Ink,
                fontSize = 15.sp
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (c in choices) {
                DropdownMenuItem(text = { Text(c.label) }, onClick = {
                    onPick(c.value); expanded = false
                })
            }
        }
    }
}

@Composable
private fun ConsentCard(vm: AssistantViewModel) {
    val checked = vm.values["consent"] == "true"
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth().clickable { vm.toggleConsent(!checked) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { vm.toggleConsent(it) })
            Spacer(Modifier.size(8.dp))
            Text(
                "I authorise Setu Finance to verify my details and fetch my credit report from a licensed bureau.",
                color = Sub, fontSize = 12.sp
            )
        }
    }
}
