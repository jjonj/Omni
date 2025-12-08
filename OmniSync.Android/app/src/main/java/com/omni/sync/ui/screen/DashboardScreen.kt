package com.omni.sync.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.omni.sync.data.repository.SignalRClient

@Composable
fun DashboardScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient?) {
    Column(modifier = modifier) {
        Text(text = "Dashboard Screen")
        // Buttons for triggers (Macros, Wake-on-LAN replacement).
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardScreenPreview() {
    DashboardScreen(signalRClient = null)
}
