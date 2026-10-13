package org.esos.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.telecom.Call

class PhoneCallActivity : ComponentActivity() {
    private var initialNumber by mutableStateOf("")
    private var callPermissionAllowed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialNumber = intent.data?.schemeSpecificPart
            ?: intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER).orEmpty()
        setContent {
            MaterialTheme {
                PhoneCallScreen(
                    initialNumber = initialNumber,
                    call = PhoneCallController.uiState,
                    callPermissionAllowed = callPermissionAllowed,
                    onCall = { number ->
                        try {
                            PhoneCallAction.startDialerCall(this, number)
                        } catch (_: RuntimeException) {
                            Toast.makeText(this, "Bellen kon niet worden gestart.", Toast.LENGTH_LONG).show()
                        }
                    },
                    onAnswer = { PhoneCallController.answer() },
                    onEnd = { PhoneCallController.disconnect() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        callPermissionAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun PhoneCallScreen(
    initialNumber: String,
    call: PhoneCallUiState?,
    callPermissionAllowed: Boolean,
    onCall: (String) -> Unit,
    onAnswer: () -> Unit,
    onEnd: () -> Unit
) {
    var number by remember(initialNumber) { mutableStateOf(initialNumber) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Telefoon", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (call == null) {
            OutlinedTextField(number, { number = it }, label = { Text("Telefoonnummer") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { onCall(number) }, enabled = callPermissionAllowed &&
                PhoneCallAction.validDialNumber(number), modifier = Modifier.fillMaxWidth()) {
                Text("Bellen")
            }
            if (!callPermissionAllowed) Text("Geef de belmachtiging om een oproep te starten.")
        } else {
            Text(call.number.ifBlank { "Onbekend nummer" }, style = MaterialTheme.typography.titleLarge)
            Text(when (call.state) {
                Call.STATE_RINGING -> "Inkomend gesprek"
                Call.STATE_CONNECTING, Call.STATE_DIALING -> "Bellen..."
                Call.STATE_ACTIVE -> "Gesprek actief"
                Call.STATE_HOLDING -> "In de wacht"
                else -> "Gesprek wordt beëindigd"
            })
            if (call.incoming) Button(onClick = onAnswer, modifier = Modifier.fillMaxWidth()) {
                Text("Opnemen")
            }
            OutlinedButton(onClick = onEnd, modifier = Modifier.fillMaxWidth()) {
                Text(if (call.incoming) "Weigeren" else "Ophangen")
            }
        }
    }
}