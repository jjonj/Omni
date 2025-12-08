package com.omni.sync.ui.screen

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import com.omni.sync.data.repository.SignalRClient

data class MouseMovePayload(val x: Float, val y: Float)

@Composable
fun TrackpadScreen(modifier: Modifier = Modifier, signalRClient: SignalRClient?) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    signalRClient?.sendPayload(
                        "MOUSE_MOVE",
                        MouseMovePayload(dragAmount.x, dragAmount.y)
                    )
                }
            }
    ) {
        Text(text = "Trackpad Screen")
    }
}

@Preview(showBackground = true)
@Composable
fun TrackpadScreenPreview() {
    TrackpadScreen(signalRClient = null)
}
