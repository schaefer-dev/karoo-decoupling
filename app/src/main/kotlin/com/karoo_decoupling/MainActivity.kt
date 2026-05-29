package com.karoo_decoupling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.karoo_decoupling.data.SettingsRepository
import com.karoo_decoupling.data.WBalSettings
import kotlinx.coroutines.launch

/**
 * Settings screen for the W'bal fields. Reachable from the Karoo extensions list via the
 * CONFIGURE_EXTENSION intent (and from the app launcher). Lets the rider set Critical Power
 * and W'. Writes go to [SettingsRepository] (DataStore), which the extension observes live.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SettingsRepository(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(repository)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val settings by remember { repository.settingsFlow }.collectAsState(initial = WBalSettings())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "W' Balance", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Set your Critical Power and anaerobic work capacity (W'). " +
                "These drive the live W'bal fields.",
            style = MaterialTheme.typography.bodyMedium,
        )

        IntField(
            label = "Critical Power (W)",
            // Show an empty box when unconfigured rather than a misleading 0.
            value = settings.criticalPower.takeIf { it > 0 },
            onCommit = { scope.launch { repository.updateCriticalPower(it) } },
        )
        IntField(
            label = "W' (J)",
            value = settings.wPrimeMax,
            onCommit = { scope.launch { repository.updateWPrime(it) } },
        )
    }
}

@Composable
private fun IntField(
    label: String,
    value: Int?,
    onCommit: (Int) -> Unit,
) {
    // Keep local text state so the user can clear/retype freely; commit valid ints on change.
    val text = remember(value) { androidx.compose.runtime.mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text.value,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }
            text.value = digits
            digits.toIntOrNull()?.let(onCommit)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
