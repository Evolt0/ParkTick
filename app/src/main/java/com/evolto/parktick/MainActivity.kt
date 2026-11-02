package com.evolto.parktick

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.evolto.parktick.ui.ParkTickApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as ParkTickApplication).graph
        setContent {
            ParkTickApp(graph, onLeaveApp = { moveTaskToBack(true) })
        }
    }
}
