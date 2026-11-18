package org.esos.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Schermvullende vraag tijdens de countdown, ook boven het vergrendelscherm. */
class CountdownActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            var remaining by remember { mutableIntStateOf(AlertRuntime.remainingSeconds()) }
            LaunchedEffect(Unit) {
                while (AlertRuntime.state() == AlertState.COUNTDOWN) {
                    remaining = AlertRuntime.remainingSeconds()
                    delay(250)
                }
                finish()
            }
            CountdownScreen(
                remainingSeconds = remaining,
                onOk = {
                    AlertRuntime.cancel(this)
                    finish()
                },
                onHelp = {
                    AlertRuntime.sendNow(this)
                    finish()
                }
            )
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, CountdownActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@androidx.compose.runtime.Composable
private fun CountdownScreen(remainingSeconds: Int, onOk: () -> Unit, onHelp: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Ruimte bovenaan: een meldingsbanner mag de vraag niet bedekken.
        Spacer(Modifier.height(160.dp))
        Text("Gaat het goed?", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.Black,
            textAlign = TextAlign.Center)
        Text("Anders waarschuwt e-SOS over", fontSize = 22.sp, color = Color.Black, textAlign = TextAlign.Center)
        Text("$remainingSeconds", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA8332A))
        Text("seconden je noodcontacten.", fontSize = 22.sp, color = Color.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Button(onClick = onOk, modifier = Modifier.fillMaxWidth().height(120.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B6E2E))) {
            Text("Ja, alles goed", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Button(onClick = onHelp, modifier = Modifier.fillMaxWidth().height(120.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA8332A))) {
            Text("Nee, haal hulp", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}
