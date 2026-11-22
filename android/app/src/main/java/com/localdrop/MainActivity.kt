package com.localdrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.localdrop.ui.HomeScreen
import com.localdrop.ui.PairingSenderScreen
import com.localdrop.ui.PairingReceiverScreen

enum class Route { Home, Send, Receive }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    var route by remember { mutableStateOf(Route.Home) }
    when (route) {
        Route.Home -> HomeScreen(onSend = { route = Route.Send }, onReceive = { route = Route.Receive }, onShareApp = { /* ShareAppScreen */ })
        Route.Send -> PairingSenderScreen(onBack = { route = Route.Home })
        Route.Receive -> PairingReceiverScreen(onBack = { route = Route.Home })
    }
}
