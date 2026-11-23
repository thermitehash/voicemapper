package com.thrivestorm2.voicemapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thrivestorm2.voicemapper.VoiceModel

/** Covers the app until the biometric or screen-lock check passes. */
@Composable
fun LockScreen(model: VoiceModel) {
    LaunchedEffect(Unit) { model.unlock() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, null, Modifier.size(48.dp), tint = Accent)
        Text("VoiceMapper is locked", style = MaterialTheme.typography.titleLarge)
        model.lockError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = { model.unlock() }) { Text("Unlock") }
    }
}
