package com.omni.sync.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omni.sync.CallAudioProbeActivity

/** A persistent home for opt-in experiments and disposable diagnostic tools. */
@Composable
fun TestsScreen() {
    val context = LocalContext.current
    var showSwipePrototype by remember { mutableStateOf(false) }
    if (showSwipePrototype) {
        SwipePrototypeScreen(onBack = { showSwipePrototype = false })
        return
    }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Prototypes")
        Text("Experimental features and diagnostics. Nothing runs automatically.")
        Button(onClick = { showSwipePrototype = true }) {
            Text("English swipe / Danish tap keyboard")
        }
        Button(onClick = { context.startActivity(Intent(context, CallAudioProbeActivity::class.java)) }) {
            Text("Call audio capture probe")
        }
    }
}
