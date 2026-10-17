package com.omni.sync.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omni.sync.CallAudioProbeActivity

/** A persistent home for opt-in experiments and disposable diagnostic tools. */
@Composable
fun TestsScreen() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Prototypes")
        Text("Experimental features and diagnostics. Nothing runs automatically.")
        Button(onClick = { context.startActivity(Intent(context, CallAudioProbeActivity::class.java)) }) {
            Text("Call audio capture probe")
        }
    }
}

