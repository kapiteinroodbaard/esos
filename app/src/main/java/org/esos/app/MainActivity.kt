package org.esos.app

import android.Manifest
import android.app.ActivityManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private var permissionVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CheckInScheduler.schedule(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF005E55),
            surface = Color(0xFFF9FAF7), onSurface = Color(0xFF182524))) { Home() } }
    }

    override fun onResume() {
        super.onResume()
        permissionVersion++
    }

    @Composable
    private fun Home() {
        val settings = remember { AppSettings(this) }
        val installedVersion = remember {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "onbekend"
        }
        var name by remember { mutableStateOf("") }
        var number by remember { mutableStateOf("") }
        var setupComplete by remember { mutableStateOf(settings.setupComplete) }
        var setupStep by remember { mutableIntStateOf(0) }
        var contacts by remember { mutableStateOf(settings.contacts) }
        var address by remember { mutableStateOf(settings.homeAddress) }
        var selectedSim by remember { mutableIntStateOf(settings.simId) }
        var shareGps by remember { mutableStateOf(settings.shareLocation) }
        var smsEnabled by remember { mutableStateOf(settings.smsEnabled) }
        var checkIn by remember { mutableStateOf(settings.checkInEnabled) }
        var hour by remember { mutableIntStateOf(settings.checkInHour) }
        var minute by remember { mutableIntStateOf(settings.checkInMinute) }
        var status by remember { mutableStateOf(settings.smsStatus) }
        var message by remember { mutableStateOf("") }
        var showSmsTest by remember { mutableStateOf(false) }
        var remaining by remember { mutableIntStateOf(0) }
        var activeState by remember { mutableStateOf(AlertRuntime.state()) }
        var listeningState by remember { mutableStateOf(ListeningStatus.state) }
        var listeningDetail by remember { mutableStateOf(ListeningStatus.detail) }
        var permissionRefresh by remember { mutableIntStateOf(0) }
        var selectedTab by remember { mutableIntStateOf(0) }

        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionRefresh++
            message = when {
                result[Manifest.permission.SEND_SMS] == false ->
                    "Android heeft SMS niet verleend. De aanvraag kan zonder dialoog worden afgewezen. e-SOS kan een door Android geblokkeerde toestemming niet zelf forceren."
                result.values.any { !it } -> "Niet alle machtigingen zijn verleend. Controleer de rechten hierboven en probeer het opnieuw."
                else -> ""
            }
        }
        val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { contactUri ->
            if (contactUri == null) {
                message = "Geen contact geselecteerd."
            } else {
                try {
                    val contact = contentResolver.query(contactUri,
                        arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                        null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
                    }
                    if (contact == null) {
                        message = "Contact niet gevonden."
                    } else {
                        val (contactId, contactName) = contact
                        val phone = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId),
                            "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                                "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, " +
                                "${ContactsContract.CommonDataKinds.Phone._ID} ASC"
                        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0)?.trim() else null }
                        if (phone.isNullOrBlank()) {
                            message = "Voor dit contact is geen telefoonnummer opgeslagen."
                        } else {
                            name = contactName.orEmpty()
                            number = android.telephony.PhoneNumberUtils.normalizeNumber(phone)
                            message = "Eerste nummer voor ${name} geselecteerd."
                        }
                    }
                } catch (_: SecurityException) {
                    message = "Geef toegang tot contacten en probeer het opnieuw."
                } catch (_: RuntimeException) {
                    message = "Contactgegevens konden niet worden gelezen."
                }
            }
        }
        val contactPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRefresh++
            if (granted) contactPicker.launch(null)
            else message = "Contacttoegang is nodig om een contact te selecteren."
        }
        LaunchedEffect(permissionVersion, permissionRefresh) {
            while (true) {
                status = settings.smsStatus
                remaining = AlertRuntime.remainingSeconds()
                activeState = AlertRuntime.state()
                listeningState = ListeningStatus.state
                listeningDetail = ListeningStatus.detail
                delay(500)
            }
        }
        val phoneAllowed = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val smsAllowed = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val notificationsAllowed = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val fineAllowed = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundAllowed = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val microphoneAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val contactsAllowed = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val backgroundRestricted = getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val powerManager = getSystemService(PowerManager::class.java)
        val batteryOptimizationDisabled = powerManager.isIgnoringBatteryOptimizations(packageName)
        val powerSaveMode = powerManager.isPowerSaveMode
        val subscriptions = if (phoneAllowed) try {
            getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) { emptyList() } else emptyList()
        val basePermissionsGranted = phoneAllowed && smsAllowed && notificationsAllowed && microphoneAllowed && fineAllowed
        val allPermissionsGranted = basePermissionsGranted && backgroundAllowed
        val allSetupRequirementsMet = allPermissionsGranted && !backgroundRestricted &&
            batteryOptimizationDisabled && !powerSaveMode
        val permissionStatuses = listOf(
            "Microfoon" to microphoneAllowed,
            "Meldingen" to notificationsAllowed,
            "SMS versturen" to smsAllowed,
            "Telefoonstatus" to phoneAllowed,
            "Precieze locatie" to fineAllowed,
            "Locatie altijd toestaan" to backgroundAllowed,
            "Achtergrondgebruik niet beperkt" to !backgroundRestricted,
            "Batterijoptimalisatie uit" to batteryOptimizationDisabled,
            "Energiebesparing uit" to !powerSaveMode
        )
        val otherBasePermissionsMissing = !phoneAllowed || !notificationsAllowed || !microphoneAllowed || !fineAllowed
        val permissionsToRequest = buildList {
            if (!microphoneAllowed) add(Manifest.permission.RECORD_AUDIO)
            if (!notificationsAllowed) add(Manifest.permission.POST_NOTIFICATIONS)
            if (!phoneAllowed) add(Manifest.permission.READ_PHONE_STATE)
            if (!fineAllowed) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.toTypedArray()
        val openAppSettings = {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }
        val requestBatteryOptimizationExemption = {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
        val openBatterySaverSettings = {
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }

        if (showSmsTest) AlertDialog(onDismissRequest = { showSmsTest = false },
            title = { Text("Echte SMS-test versturen?") },
            text = { Text("Dit kost mogelijk geld en stuurt nu een echt testbericht naar: ${contacts.joinToString { it.name + " (" + it.number + ")" }}.") },
            confirmButton = { TextButton(onClick = {
                showSmsTest = false
                try {
                    SmsAlertSender(this, settings).send(System.currentTimeMillis(), "e-SOS TEST: dit is een afgesproken testbericht, geen alarm.")
                    message = "Testbericht aangeboden; controleer resultaat en bevestig ontvangst bij contact."
                } catch (_: RuntimeException) { message = "Testbericht mislukt. Controleer contact, SIM en machtigingen." }
            }) { Text("Verstuur test-SMS") } },
            dismissButton = { TextButton(onClick = { showSmsTest = false }) { Text("Annuleer") } })

        if (!setupComplete) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("e-SOS", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Eerste configuratie", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Stap ${setupStep + 1} van 3")
                HorizontalDivider()

                when (setupStep) {
                    0 -> {
                        Text("Rechten", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        permissionStatuses.forEach { (label, granted) ->
                            PermissionStatusRow(label, granted)
                        }
                        if (!smsAllowed) {
                            Text("SMS versturen is een beperkt Android-recht. Vraag het hieronder afzonderlijk aan. Blijft de schakelaar grijs na installatie uit een APK? Open de app-instellingen en kies via het menu rechtsboven 'Beperkte instellingen toestaan' als die optie bestaat. Probeer daarna de SMS-aanvraag opnieuw. Als het toestel of de installer dit niet toestaat, kan e-SOS geen automatische SMS versturen.",
                                color = Color(0xFFC62828))
                            OutlinedButton(onClick = { permissions.launch(arrayOf(Manifest.permission.SEND_SMS)) },
                                modifier = Modifier.fillMaxWidth()) { Text("Vraag SMS-toestemming") }
                            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"))) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Open app-instellingen")
                            }
                        }
                        if (permissionsToRequest.isNotEmpty()) {
                            Button(onClick = { permissions.launch(permissionsToRequest) },
                                modifier = Modifier.fillMaxWidth()) { Text("Vraag overige machtigingen") }
                        }
                        if (fineAllowed && !backgroundAllowed) {
                            Text("Open de app-machtigingen en kies bij Locatie voor 'Altijd toestaan'.")
                            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"))) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Open locatie-instellingen")
                            }
                        }
                        if (backgroundRestricted) {
                            Text("Sta e-SOS toe om op de achtergrond actief te blijven.")
                            OutlinedButton(onClick = openAppSettings, modifier = Modifier.fillMaxWidth()) {
                                Text("Open achtergrondinstellingen")
                            }
                        }
                        if (!batteryOptimizationDisabled) {
                            Text("Schakel batterijoptimalisatie voor e-SOS uit zodat Android de luisterservice niet stilzet.")
                            OutlinedButton(onClick = requestBatteryOptimizationExemption,
                                modifier = Modifier.fillMaxWidth()) { Text("Sta achtergrondgebruik toe") }
                        }
                        if (powerSaveMode) {
                            Text("Schakel de systeemmodus Energiebesparing uit.")
                            OutlinedButton(onClick = openBatterySaverSettings,
                                modifier = Modifier.fillMaxWidth()) { Text("Open energiebesparing") }
                        }
                        if (!allPermissionsGranted && otherBasePermissionsMissing) {
                            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"))) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Open instellingen voor overige rechten")
                            }
                        }
                        if (message.isNotBlank()) Text(message, color = Color(0xFFC62828))
                        Button(onClick = { setupStep = 1 }, enabled = allSetupRequirementsMet,
                            modifier = Modifier.fillMaxWidth()) { Text("Verder") }
                    }
                    1 -> {
                        Text("Contacten en SIM", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("Voeg een bereikbaar noodcontact toe en kies de SIM voor alarm-SMS.")
                        contacts.forEach { contact ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("${contact.name}: ${contact.number}", modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    contacts = contacts - contact
                                    settings.contacts = contacts
                                }) { Text("Verwijder") }
                            }
                        }
                        OutlinedTextField(name, { name = it }, label = { Text("Naam") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(number, { number = it }, label = { Text("Telefoonnummer met landcode") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val normalized = android.telephony.PhoneNumberUtils.normalizeNumber(number)
                            if (name.isBlank() || !SmsAlertSender.validNumber(normalized)) {
                                message = "Vul een naam en internationaal nummer in (+ en 8-15 cijfers)."
                            } else if (contacts.any { it.number == normalized }) {
                                message = "Dit telefoonnummer is al opgeslagen."
                            } else {
                                contacts = contacts + FamilyContact(name.trim(), normalized)
                                settings.contacts = contacts
                                name = ""; number = ""
                                message = "Contact opgeslagen."
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Voeg contact toe") }
                        Text("SIM voor alarm-SMS", fontWeight = FontWeight.Bold)
                        if (subscriptions.isEmpty()) Text("Geen actieve SIM gevonden of geen telefoontoestemming.")
                        subscriptions.forEach { subscription ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedSim == subscription.subscriptionId,
                                    onClick = {
                                        selectedSim = subscription.subscriptionId
                                        settings.simId = selectedSim
                                    })
                                Text("SIM ${subscription.simSlotIndex + 1}: ${subscription.displayName}")
                            }
                        }
                        if (message.isNotBlank()) Text(message)
                        Button(onClick = {
                            settings.simId = selectedSim
                            setupStep = 2
                            message = ""
                        }, enabled = allSetupRequirementsMet && SmsAlertSender(this@MainActivity, settings).ready(),
                            modifier = Modifier.fillMaxWidth()) { Text("Verder") }
                    }
                    else -> {
                        Text("Controle en test", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("Machtigingen, contact en SIM zijn ingesteld. De app kan nu een test-SMS versturen.")
                        Text("Een test-SMS is een echt bericht en kan kosten opleveren.")
                        if (message.isNotBlank()) Text(message)
                        OutlinedButton(onClick = { showSmsTest = true },
                            enabled = SmsAlertSender(this@MainActivity, settings).ready(),
                            modifier = Modifier.fillMaxWidth()) { Text("Stuur test-SMS...") }
                        Button(onClick = {
                            settings.setupComplete = true
                            setupComplete = true
                        }, enabled = allSetupRequirementsMet && SmsAlertSender(this@MainActivity, settings).ready(),
                            modifier = Modifier.fillMaxWidth()) { Text("Configuratie afronden") }
                    }
                }
            }
            return
        }

        val smsSender = SmsAlertSender(this@MainActivity, settings)
        val smsReady = smsSender.ready()
        val voiceDetectionOn = listeningState == ListeningState.LISTENING ||
            listeningState == ListeningState.STARTING || listeningState == ListeningState.PAUSED
        val startManualSos: () -> Unit = {
            val started = AlertRuntime.start(this@MainActivity, AlertReason.SOS)
            message = when {
                !started -> "Alarm niet gestart: controleer SMS-instellingen onder Acties."
                smsEnabled -> "Alarm gestart; 10 seconden om te annuleren."
                else -> "Alarm gestart; SMS staat uit en er wordt geen bericht verzonden."
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    val tabs = listOf("Dashboard", "SOS", "Acties", "Instellingen")
                    val symbols = listOf("◉", "!", "↗", "⚙")
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Text(symbols[index], fontSize = 18.sp) },
                            label = { Text(title) }
                        )
                    }
                }
            }
        ) { contentPadding ->
            Column(Modifier.fillMaxSize().padding(contentPadding)
                .background(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (selectedTab) {
                        0 -> {
                            Text("Dashboard", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            val protectionTitle = when {
                                listeningState == ListeningState.STARTING -> "Spraakdetectie wordt gestart"
                                listeningState == ListeningState.PAUSED -> "Spraakdetectie heeft aandacht nodig"
                                listeningState == ListeningState.LISTENING && checkIn -> "Bescherming actief"
                                listeningState == ListeningState.LISTENING -> "Spraakdetectie actief"
                                checkIn -> "Dagelijkse check-in actief"
                                else -> "Geen actieve SOS-triggers"
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text(protectionTitle, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                                        color = when {
                                            listeningState == ListeningState.PAUSED -> Color(0xFF8B5E00)
                                            listeningState == ListeningState.LISTENING || checkIn -> Color(0xFF005E55)
                                            else -> Color(0xFF9D2727)
                                        })
                                    HorizontalDivider()
                                    StatusRow("Spraakdetectie", when (listeningState) {
                                        ListeningState.LISTENING -> "Actief"
                                        ListeningState.STARTING -> "Wordt gestart"
                                        ListeningState.PAUSED -> "Gepauzeerd"
                                        ListeningState.ERROR -> "Gestopt door fout"
                                        ListeningState.STOPPED -> "Uit"
                                    }, listeningState == ListeningState.LISTENING)
                                    if (listeningDetail.isNotBlank()) Text(listeningDetail,
                                        style = MaterialTheme.typography.bodySmall)
                                    StatusRow("Dagelijkse check-in", if (checkIn) "Actief om %02d:%02d".format(hour, minute) else "Uit", checkIn)
                                    StatusRow("SMS-actie", if (!smsEnabled) "Uit" else if (smsReady) "Ingeschakeld" else "Ingeschakeld, niet ingesteld",
                                        smsEnabled && smsReady)
                                    StatusRow("Locatie delen", if (shareGps) "Ingeschakeld" else "Uit", shareGps)
                                }
                            }
                            HoldToConfirmSos(
                                countdown = activeState == AlertState.COUNTDOWN,
                                remainingSeconds = remaining,
                                onTrigger = startManualSos,
                                onCancel = { AlertRuntime.cancel(this@MainActivity) }
                            )
                            if (settings.checkInDeadline > 0) {
                                Text("Bevestiging gevraagd: alles goed?")
                                Button(onClick = {
                                    CheckInScheduler.confirm(this@MainActivity)
                                    message = "Check-in bevestigd"
                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                    Text("Alles goed")
                                }
                            }
                        }
                        1 -> {
                            Text("SOS-activering", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("Spraakdetectie start een SOS-alarm bij deze vaste frases: 'help help', 'help mij', 'ik heb hulp nodig', 'help me' of 'I need help'.")
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Spraakdetectie", fontWeight = FontWeight.SemiBold)
                                    Text("Herkent deze hulpfrases lokaal op dit toestel.", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = voiceDetectionOn, onCheckedChange = { enabled ->
                                    if (!enabled) {
                                        startService(Intent(this@MainActivity, ListeningService::class.java)
                                            .setAction(ListeningService.STOP))
                                    } else if (!microphoneAllowed || !notificationsAllowed || (smsEnabled && !smsReady)) {
                                        message = when {
                                            !microphoneAllowed -> "Geef eerst microfoontoegang via Instellingen."
                                            !notificationsAllowed -> "Geef eerst meldingentoegang via Instellingen."
                                            else -> "Stel eerst SMS-contact, SIM en machtigingen in via Acties."
                                        }
                                    } else {
                                        try {
                                            ContextCompat.startForegroundService(this@MainActivity,
                                                Intent(this@MainActivity, ListeningService::class.java)
                                                    .setAction(ListeningService.START))
                                            message = "Modellen worden lokaal geladen; wacht op 'Luistert'."
                                        } catch (_: RuntimeException) {
                                            message = "Spraakdetectie kon niet starten; controleer de machtigingen."
                                        }
                                    }
                                })
                            }
                            if (!microphoneAllowed) OutlinedButton(onClick = {
                                permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            }) { Text("Vraag microfoontoegang") }
                            if (smsEnabled && !smsReady) Text("De SMS-actie is nog niet ingesteld. Configureer deze onder Acties om spraakdetectie te starten.")
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Dagelijkse check-in", fontWeight = FontWeight.SemiBold)
                                    Text("Elke dag om %02d:%02d, met 2 uur om te bevestigen.".format(hour, minute),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = checkIn, onCheckedChange = { enabled ->
                                    if (enabled && !notificationsAllowed) {
                                        message = "Geef eerst meldingentoegang via Instellingen."
                                    } else if (enabled && smsEnabled && !smsReady) {
                                        message = "Stel eerst SMS-contact, SIM en machtigingen in via Acties."
                                    } else {
                                        checkIn = enabled
                                        settings.checkInEnabled = enabled
                                        if (!enabled) settings.checkInDeadline = 0L
                                        CheckInScheduler.schedule(this@MainActivity)
                                    }
                                })
                            }
                            OutlinedButton(onClick = {
                                TimePickerDialog(this@MainActivity, { _, chosenHour, chosenMinute ->
                                    hour = chosenHour
                                    minute = chosenMinute
                                    settings.checkInHour = hour
                                    settings.checkInMinute = minute
                                    CheckInScheduler.schedule(this@MainActivity)
                                }, hour, minute, true).show()
                            }) { Text("Tijd wijzigen: %02d:%02d".format(hour, minute)) }
                            if (!smsEnabled && checkIn) Text("SMS staat uit; bij een gemiste check-in volgt geen SMS-bericht.")
                            if (settings.checkInDeadline > 0) {
                                Text("Check-in wacht op bevestiging.")
                                Button(onClick = {
                                    CheckInScheduler.confirm(this@MainActivity)
                                    message = "Check-in bevestigd"
                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                    Text("Alles goed")
                                }
                            }
                            HorizontalDivider()
                            if (activeState == AlertState.COUNTDOWN) {
                                Text("SOS-alarm over $remaining seconden", fontSize = 23.sp,
                                    fontWeight = FontWeight.Bold)
                                OutlinedButton(onClick = { AlertRuntime.cancel(this@MainActivity) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                                    Text("Annuleer alarm")
                                }
                            }
                            if (activeState == AlertState.COUNTDOWN) HorizontalDivider()
                            Text("Handmatig", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            if (activeState != AlertState.COUNTDOWN) {
                                OutlinedButton(onClick = {
                                    message = if (AlertRuntime.start(this@MainActivity, AlertReason.SOS, simulate = true))
                                        "Simulatie gestart; er wordt geen echte SMS verzonden." else "Er is al een alarm actief."
                                }, modifier = Modifier.fillMaxWidth()) { Text("Test zonder SMS") }
                            }
                            if (message.isNotBlank()) Text(message)
                        }
                        2 -> {
                            Text("Acties", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("Kies welke acties e-SOS bij een alarm uitvoert.")
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("SMS versturen", fontWeight = FontWeight.SemiBold)
                                    Text(if (smsEnabled) "Noodcontacten ontvangen alarmberichten." else "Er worden geen SMS-berichten verstuurd.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = smsEnabled, onCheckedChange = { enabled ->
                                    smsEnabled = enabled
                                    settings.smsEnabled = enabled
                                })
                            }
                            if (smsEnabled) {
                                HorizontalDivider()
                                Text("Noodcontacten", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                contacts.forEach { contact ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Text("${contact.name}: ${contact.number}", modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            contacts = contacts - contact
                                            settings.contacts = contacts
                                        }) { Text("Verwijder") }
                                    }
                                }
                                OutlinedButton(onClick = {
                                    if (contactsAllowed) contactPicker.launch(null)
                                    else contactPermission.launch(Manifest.permission.READ_CONTACTS)
                                }, modifier = Modifier.fillMaxWidth()) { Text("Selecteer uit contacten") }
                                OutlinedTextField(name, { name = it }, label = { Text("Naam") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(number, { number = it }, label = { Text("Telefoonnummer met landcode") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                                Button(onClick = {
                                    val normalized = android.telephony.PhoneNumberUtils.normalizeNumber(number)
                                    if (name.isBlank() || !SmsAlertSender.validNumber(normalized)) {
                                        message = "Vul een naam en internationaal nummer in (+ en 8-15 cijfers)."
                                    } else if (contacts.any { it.number == normalized }) {
                                        message = "Dit telefoonnummer is al opgeslagen."
                                    } else {
                                        contacts = contacts + FamilyContact(name.trim(), normalized)
                                        settings.contacts = contacts
                                        name = ""
                                        number = ""
                                        message = "Contact opgeslagen."
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Voeg contact toe") }
                                OutlinedTextField(address, { address = it }, label = { Text("Huisadres (optioneel)") },
                                    modifier = Modifier.fillMaxWidth())
                                Text("SIM voor alarm-SMS", fontWeight = FontWeight.SemiBold)
                                if (subscriptions.isEmpty()) Text("Geen actieve SIM gevonden of geen telefoontoestemming.")
                                subscriptions.forEach { subscription ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = selectedSim == subscription.subscriptionId, onClick = {
                                            selectedSim = subscription.subscriptionId
                                            settings.simId = selectedSim
                                        })
                                        Text("SIM ${subscription.simSlotIndex + 1}: ${subscription.displayName}")
                                    }
                                }
                                Button(onClick = {
                                    settings.homeAddress = address
                                    settings.simId = selectedSim
                                    message = "SIM en adres opgeslagen."
                                }, modifier = Modifier.fillMaxWidth()) { Text("Bewaar SMS-instellingen") }
                                if (!smsAllowed) {
                                    Text("SMS-toestemming is nodig om berichten te versturen.", color = Color(0xFFC62828))
                                    OutlinedButton(onClick = {
                                        permissions.launch(arrayOf(Manifest.permission.SEND_SMS))
                                    }) { Text("Vraag SMS-toestemming") }
                                }
                                OutlinedButton(onClick = { showSmsTest = true }, enabled = smsReady,
                                    modifier = Modifier.fillMaxWidth()) { Text("Test-SMS versturen") }
                                Text(status)
                            }
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Locatie delen", fontWeight = FontWeight.SemiBold)
                                    Text(if (shareGps) "Een recente nauwkeurige GPS-locatie wordt aan het alarmbericht toegevoegd wanneer die beschikbaar is."
                                        else "Er wordt geen GPS-locatie aan het alarmbericht toegevoegd.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = shareGps, onCheckedChange = {
                                    shareGps = it
                                    settings.shareLocation = it
                                })
                            }
                            if (shareGps && !fineAllowed) OutlinedButton(onClick = {
                                permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION))
                            }) { Text("Vraag locatietoegang") }
                            if (shareGps && fineAllowed && !backgroundAllowed) {
                                Text("Kies bij locatierechten voor 'Altijd toestaan' om GPS tijdens een alarm te gebruiken.")
                                OutlinedButton(onClick = {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:$packageName")))
                                }) { Text("Open app-instellingen") }
                            }
                            if (message.isNotBlank()) Text(message)
                        }
                        else -> {
                            Text("Instellingen", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("Machtigingen", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            permissionStatuses.forEach { (label, granted) -> PermissionStatusRow(label, granted) }
                            if (backgroundRestricted) {
                                Text("Sta e-SOS toe om op de achtergrond actief te blijven.")
                                OutlinedButton(onClick = openAppSettings,
                                    modifier = Modifier.fillMaxWidth()) { Text("Open achtergrondinstellingen") }
                            }
                            if (!batteryOptimizationDisabled) {
                                Text("Schakel batterijoptimalisatie voor e-SOS uit zodat Android de luisterservice niet stilzet.")
                                OutlinedButton(onClick = requestBatteryOptimizationExemption,
                                    modifier = Modifier.fillMaxWidth()) { Text("Sta achtergrondgebruik toe") }
                            }
                            if (powerSaveMode) {
                                Text("Schakel de systeemmodus Energiebesparing uit.")
                                OutlinedButton(onClick = openBatterySaverSettings,
                                    modifier = Modifier.fillMaxWidth()) { Text("Open energiebesparing") }
                            }
                            PermissionStatusRow("Contactenlijst (optioneel)", contactsAllowed)
                            if (!contactsAllowed) OutlinedButton(onClick = {
                                contactPermission.launch(Manifest.permission.READ_CONTACTS)
                            }, modifier = Modifier.fillMaxWidth()) { Text("Vraag contacttoegang") }
                            if (permissionsToRequest.isNotEmpty()) Button(onClick = {
                                permissions.launch(permissionsToRequest)
                            }, modifier = Modifier.fillMaxWidth()) { Text("Vraag overige machtigingen") }
                            if (!smsAllowed) {
                                Text("SMS is een beperkt Android-recht. Als de aanvraag wordt geblokkeerd, open App-info en sta beperkte instellingen toe indien beschikbaar.",
                                    color = Color(0xFFC62828))
                                OutlinedButton(onClick = {
                                    permissions.launch(arrayOf(Manifest.permission.SEND_SMS))
                                }, modifier = Modifier.fillMaxWidth()) { Text("Vraag SMS-toestemming") }
                            }
                            if (fineAllowed && !backgroundAllowed) {
                                Text("Locatie altijd toestaan moet apart in Android worden ingeschakeld.")
                                OutlinedButton(onClick = {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:$packageName")))
                                }, modifier = Modifier.fillMaxWidth()) { Text("Open rechten in Android") }
                            }
                            if (!allPermissionsGranted || !smsAllowed) OutlinedButton(onClick = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName")))
                            }, modifier = Modifier.fillMaxWidth()) { Text("Open app-instellingen") }
                            HorizontalDivider()
                            Text("Over e-SOS", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            Text("Gemaakt door Kapitein Roodbaard")
                            Text("Versie $installedVersion")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PermissionStatusRow(label: String, granted: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (granted) "✓" else "✕",
                color = if (granted) Color(0xFF218739) else Color(0xFFC62828),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(32.dp)
            )
            Text(label)
            Spacer(Modifier.weight(1f))
            Text(if (granted) "Verleend" else "Niet verleend",
                color = if (granted) Color(0xFF218739) else Color(0xFFC62828))
        }
    }

    @Composable
    private fun StatusRow(label: String, value: String, active: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("●", color = if (active) Color(0xFF218739) else Color(0xFF9D2727), fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Text(label, modifier = Modifier.weight(1f))
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HoldToConfirmSos(
    countdown: Boolean,
    remainingSeconds: Int,
    onTrigger: () -> Unit,
    onCancel: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val currentOnTrigger by rememberUpdatedState(onTrigger)

    LaunchedEffect(isPressed) {
        if (isPressed) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 3_000, easing = LinearEasing))
        } else {
            progress.snapTo(0f)
        }
    }

    if (countdown) {
        Text("SOS-alarm over $remainingSeconds seconden", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text("Annuleer alarm")
        }
    } else {
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().height(144.dp).pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    val heldForThreeSeconds = withTimeoutOrNull(3_000L) { tryAwaitRelease() } == null
                    isPressed = false
                    if (heldForThreeSeconds) currentOnTrigger()
                })
            },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA8332A), contentColor = Color.White)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("SOS", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(if (isPressed) "Vasthouden: ${(3 - (progress.value * 3).toInt()).coerceIn(1, 3)} s" else "3 seconden ingedrukt houden")
                LinearProgressIndicator(
                    progress = { progress.value },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f)
                )
            }
        }
    }
}