package top.yurin.voxcpm2.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yurin.voxcpm2.Progress

@Composable
fun App() {
    val model = remember { SpeechModel() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { model.close() }
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("VoxCPM2 Example", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = model.modelDirectory,
                onValueChange = { model.modelDirectory = it },
                label = { Text("Model directory (absolute path)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = model.text,
                onValueChange = { model.text = it },
                label = { Text("Text to synthesize") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterChip(
                    selected = !model.useVulkan,
                    onClick = { model.useVulkan = false },
                    label = { Text("CPU") },
                )
                FilterChip(
                    selected = model.useVulkan,
                    onClick = { model.useVulkan = true },
                    label = { Text("Vulkan") },
                )
                OutlinedTextField(
                    value = model.threadsText,
                    onValueChange = { model.threadsText = it },
                    label = { Text("Threads (0 = auto)") },
                    modifier = Modifier.width(160.dp),
                    singleLine = true,
                )
            }

            when (val current = model.state) {
                is UiState.Idle -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { model.load(scope) },
                        enabled = model.modelDirectory.isNotBlank(),
                    ) { Text("Load model") }
                    OutlinedButton(onClick = { model.downloadModel(scope) }) {
                        Text("Download model (~4.9 GB)")
                    }
                    Text(
                        "Downloads to ${defaultModelDirectory()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is UiState.Loading -> {
                    ProgressView(current.progress)
                    OutlinedButton(onClick = { model.cancelActiveWork() }) { Text("Cancel") }
                }

                is UiState.Downloading -> {
                    DownloadProgressView(current.progress)
                    OutlinedButton(onClick = { model.cancelActiveWork() }) { Text("Cancel") }
                }

                is UiState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { model.synthesize(scope) }) { Text("Synthesize") }
                    OutlinedButton(onClick = { model.close() }) { Text("Unload") }
                }

                is UiState.Synthesizing -> {
                    ProgressView(current.progress)
                    OutlinedButton(onClick = { model.cancelActiveWork() }) { Text("Cancel") }
                }

                is UiState.Result -> {
                    Text("Generated mono PCM ready.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { model.playLastResult() }) { Text("Play") }
                        OutlinedButton(onClick = { model.stopPlayback() }) { Text("Stop") }
                        OutlinedButton(onClick = { model.saveLastResultAsWav() }) { Text("Save WAV") }
                    }
                    current.savedTo?.let { Text("Saved to $it", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { model.synthesize(scope) }) { Text("Synthesize again") }
                }

                is UiState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Error: ${current.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { model.load(scope) }) { Text("Retry") }
                        OutlinedButton(onClick = { model.downloadModel(scope) }) {
                            Text("Download model (~4.9 GB)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressView(progress: Progress?) {
    if (progress == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${progress.label} (${progress.completed}/${progress.total})")
        val fraction = progress.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DownloadProgressView(progress: DownloadProgress?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Contacting download source…")
            return
        }
        Text("${progress.currentFile} — ${formatBytes(progress.bytesDone)} / ${formatBytes(progress.bytesTotal)}")
        val fraction = progress.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_000) {
        return "$bytes B"
    }
    val megabytes = bytes / 1_000_000.0
    if (megabytes < 1_000.0) {
        return "${megabytes.toInt()} MB"
    }
    val gigabytes = megabytes / 1_000.0
    return "${(gigabytes * 100).toInt() / 100.0} GB"
}
