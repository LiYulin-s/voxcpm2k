package top.yurin.voxcpm2.example

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "VoxCPM2 Example") {
        App()
    }
}
