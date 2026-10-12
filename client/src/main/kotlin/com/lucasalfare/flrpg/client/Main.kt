package com.lucasalfare.flrpg.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "FL-RPG",
    ) {
      MaterialTheme {
        HelloWorldScreen()
      }
    }
  }
}

@Composable
fun HelloWorldScreen() {
  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Hello, world!")
  }
}
