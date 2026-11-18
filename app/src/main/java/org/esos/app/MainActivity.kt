package org.esos.app

import android.Manifest
import android.app.AlarmManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal fun moveEmergencyContact(contacts: List<FamilyContact>, from: Int, to: Int): List<FamilyContact> {
    if (from !in contacts.indices || to !in contacts.indices || from == to) return contacts
    return contacts.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
private fun PhraseEditor(
    title: String,
    noun: String,
    phrases: List<String>,
    onChange: (List<String>) -> Unit,
    onMessage: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (phrases.isEmpty()) Text("Geen $noun" + "nen ingesteld.")
    phrases.forEach { phrase ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(phrase, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                onChange(phrases - phrase)
                onMessage("Wijzigingen gelden na opnieuw starten van spraakdetectie.")
            }) { Text("Verwijder") }
        }
    }
    OutlinedTextField(input, { input = it }, label = { Text("Nieuwe $noun") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val phrase = WakePhrases.unique(listOf(input)).firstOrNull()
        when {
            phrase == null -> onMessage("Voer een $noun in.")
            phrases.any { WakePhrases.normalize(it) == WakePhrases.normalize(phrase) } ->
                onMessage("Deze $noun staat al in de lijst.")
            else -> {
                onChange(WakePhrases.unique(phrases + phrase))
                input = ""
                onMessage("${noun.replaceFirstChar { it.uppercase() }} opgeslagen. Herstart spraakdetectie om deze toe te passen.")
            }
        }
    }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text("${noun.replaceFirstChar { it.uppercase() }} toevoegen")
    }
}

@Composable
private fun EmergencyContacts(
    contacts: List<FamilyContact>,
    onChange: (List<FamilyContact>) -> Unit,
    onAdd: () -> Unit
) {
    var pendingRemoval by remember { mutableStateOf<FamilyContact?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (contacts.size > 1) Text("Bovenaan staat wie als eerste wordt gebeld.")
        contacts.forEachIndexed { index, contact ->
            key(contact.number) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${index + 1}. ${contact.name}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                            Text(contact.number, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onChange(moveEmergencyContact(contacts, index, index - 1)) },
                            enabled = index > 0, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "${contact.name} omhoog",
                                modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { onChange(moveEmergencyContact(contacts, index, index + 1)) },
                            enabled = index < contacts.lastIndex, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "${contact.name} omlaag",
                                modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { pendingRemoval = contact }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Verwijder ${contact.name}",
                                modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Noodcontact toevoegen")
        }
    }
    pendingRemoval?.let { contact ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("${contact.name} verwijderen?") },
            text = { Text("${contact.name} wordt dan niet meer gewaarschuwd bij een noodgeval.") },
            confirmButton = {
                TextButton(onClick = {
                    onChange(contacts - contact)
                    pendingRemoval = null
                }) { Text("Verwijderen") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Niet verwijderen") } }
        )
    }
}

class MainActivity : ComponentActivity() {
    private var permissionVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CheckInScheduler.clear(this)
        InactivityMonitorService.startIfEnabled(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF005E55),
            surface = Color(0xFFF9FAF7), onSurface = Color(0xFF182524)), typography = seniorTypography()) { Home() } }
    }

    override fun onResume() {
        super.onResume()
        permissionVersion++
        AlertRuntime.restoreActiveSosNotification(this)
        SosCallScheduler.restore(this)
    }

    @Composable
    private fun Home() {
        val settings = remember { AppSettings(this) }
        val installedVersion = remember {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "onbekend"
        }
        var setupComplete by remember { mutableStateOf(settings.setupComplete) }
        var setupStep by remember { mutableIntStateOf(0) }
        var contacts by remember { mutableStateOf(settings.contacts) }
        var selectedSim by remember { mutableIntStateOf(settings.simId) }
        var shareGps by remember { mutableStateOf(settings.shareLocation) }
        var wakePhrases by remember { mutableStateOf(settings.wakePhrases) }
        var cancelPhrases by remember { mutableStateOf(settings.cancelPhrases) }
        var smsEnabled by remember { mutableStateOf(settings.smsEnabled) }
        var callEnabled by remember { mutableStateOf(settings.callEnabled) }
        var spokenFeedbackEnabled by remember { mutableStateOf(settings.spokenFeedbackEnabled) }
        var alarmSoundEnabled by remember { mutableStateOf(settings.alarmSoundEnabled) }
        var alarmSoundMode by remember { mutableStateOf(settings.alarmSoundMode) }
        var vibrationEnabled by remember { mutableStateOf(settings.vibrationEnabled) }
        var vibrationContinuous by remember { mutableStateOf(settings.vibrationContinuous) }
        var fallDetectionOn by remember { mutableStateOf(settings.fallDetectionEnabled) }
        var fallSensitivity by remember { mutableIntStateOf(settings.fallSensitivity) }
        var fallServiceActive by remember { mutableStateOf(FallDetectionStatus.active) }
        var fallDetail by remember { mutableStateOf(FallDetectionStatus.detail) }
        var inactivityEnabled by remember { mutableStateOf(settings.inactivityEnabled) }
        var inactivityHours by remember { mutableIntStateOf(settings.inactivityHours) }
        var inactivitySleepStartMinute by remember { mutableIntStateOf(settings.inactivitySleepStartMinute) }
        var inactivitySleepEndMinute by remember { mutableIntStateOf(settings.inactivitySleepEndMinute) }
        var message by remember { mutableStateOf("") }
        var remaining by remember { mutableIntStateOf(0) }
        var activeState by remember { mutableStateOf(AlertRuntime.state()) }
        var sosModeActive by remember { mutableStateOf(settings.sosModeActive) }
        var callRoundStatus by remember { mutableStateOf(SosCallScheduler.status(this)) }
        var smsStatus by remember { mutableStateOf(AlertRuntime.smsStatus(this)) }
        var overlayVisible by remember { mutableStateOf(SosOverlay.visible) }
        var listeningState by remember { mutableStateOf(ListeningStatus.state) }
        var listeningDetail by remember { mutableStateOf(ListeningStatus.detail) }
        var permissionRefresh by remember { mutableIntStateOf(0) }
        var selectedTab by remember { mutableIntStateOf(0) }

        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionRefresh++
            if (result[Manifest.permission.CALL_PHONE] == true) {
                callEnabled = true
                settings.callEnabled = true
            }
            message = when {
                result[Manifest.permission.SEND_SMS] == false ->
                    "Android heeft SMS niet verleend. De aanvraag kan zonder dialoog worden afgewezen. e-SOS kan een door Android geblokkeerde toestemming niet zelf forceren."
                result[Manifest.permission.CALL_PHONE] == false ->
                    "Android heeft de belmachtiging niet verleend. De belactie blijft uit."
                result.values.any { !it } -> "Niet alle machtigingen zijn verleend. Controleer de rechten hierboven en probeer het opnieuw."
                else -> ""
            }
        }
        val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val contactUri = result.data?.data?.takeIf { result.resultCode == RESULT_OK }
            if (contactUri == null) {
                message = "Geen contact geselecteerd."
            } else {
                try {
                    val contact = contentResolver.query(contactUri,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER),
                        null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
                    }
                    if (contact == null) {
                        message = "Contact niet gevonden."
                    } else {
                        val (contactName, phone) = contact
                        val normalized = SmsAlertSender.toInternational(this@MainActivity, phone.orEmpty())
                        when {
                            !SmsAlertSender.validNumber(normalized) ->
                                message = "Het nummer van dit contact klopt niet. Controleer het in je contactenlijst."
                            contacts.any { it.number == normalized } ->
                                message = "Dit telefoonnummer is al opgeslagen."
                            else -> {
                                contacts = contacts + FamilyContact(contactName.orEmpty().ifBlank { normalized }, normalized)
                                settings.contacts = contacts
                                message = "Contact opgeslagen."
                            }
                        }
                    }
                } catch (_: RuntimeException) {
                    message = "Contactgegevens konden niet worden gelezen."
                }
            }
        }
        val pickContact: () -> Unit = {
            try {
                contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            } catch (_: android.content.ActivityNotFoundException) {
                message = "Geen contacten-app beschikbaar."
            }
        }
        val enableFallDetection: () -> Unit = {
            fallDetectionOn = true
            settings.fallDetectionEnabled = true
            try {
                FallDetectionService.start(this@MainActivity)
                message = ""
            } catch (_: RuntimeException) {
                fallDetectionOn = false
                settings.fallDetectionEnabled = false
                message = "Valdetectie kon niet starten; controleer de machtigingen."
            }
        }
        val fallPermissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            permissionRefresh++
            if (result[Manifest.permission.ACTIVITY_RECOGNITION] == true &&
                result[Manifest.permission.POST_NOTIFICATIONS] == true
            ) enableFallDetection()
            else message = "Bewegingsherkenning en meldingentoegang zijn nodig voor valdetectie."
        }
        LaunchedEffect(permissionVersion, permissionRefresh) {
            while (true) {
                remaining = AlertRuntime.remainingSeconds()
                activeState = AlertRuntime.state()
                sosModeActive = settings.sosModeActive
                callRoundStatus = SosCallScheduler.status(this@MainActivity)
                smsStatus = AlertRuntime.smsStatus(this@MainActivity)
                overlayVisible = SosOverlay.visible
                listeningState = ListeningStatus.state
                listeningDetail = ListeningStatus.detail
                fallServiceActive = FallDetectionStatus.active
                fallDetail = FallDetectionStatus.detail
                delay(500)
            }
        }
        val phoneAllowed = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val smsAllowed = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val notificationsAllowed = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val activityRecognitionAllowed = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val fineAllowed = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundAllowed = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val microphoneAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val callPermissionAllowed = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val overlayAllowed = Settings.canDrawOverlays(this)
        val fullScreenAllowed = getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
        val exactAlarmsAllowed = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val backgroundRestricted = getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val powerManager = getSystemService(PowerManager::class.java)
        val batteryOptimizationDisabled = powerManager.isIgnoringBatteryOptimizations(packageName)
        val powerSaveMode = powerManager.isPowerSaveMode
        val subscriptions = if (phoneAllowed) try {
            getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) { emptyList() } else emptyList()
        val singleSimId = subscriptions.singleOrNull()?.subscriptionId
        LaunchedEffect(singleSimId) {
            if (singleSimId != null) {
                selectedSim = singleSimId
                settings.simId = singleSimId
            }
        }
        val basePermissionsGranted = phoneAllowed && smsAllowed && notificationsAllowed && microphoneAllowed && fineAllowed
        val allPermissionsGranted = basePermissionsGranted && backgroundAllowed
        val allSetupRequirementsMet = allPermissionsGranted && callPermissionAllowed && overlayAllowed && fullScreenAllowed &&
            exactAlarmsAllowed && !backgroundRestricted &&
            batteryOptimizationDisabled && !powerSaveMode
        val permissionStatuses = listOf(
            "Microfoon" to microphoneAllowed,
            "Meldingen tonen" to notificationsAllowed,
            "SMS versturen" to smsAllowed,
            "SIM-kaart lezen" to phoneAllowed,
            "Bellen" to callPermissionAllowed,
            "Alarmscherm tonen" to fullScreenAllowed,
            "Rode rand tonen" to overlayAllowed,
            "Op tijd opnieuw bellen" to exactAlarmsAllowed,
            "Locatie" to fineAllowed,
            "Locatie altijd toestaan" to backgroundAllowed,
            "Op de achtergrond werken" to !backgroundRestricted,
            "Batterijbesparing voor e-SOS uit" to batteryOptimizationDisabled,
            "Energiebesparing uit" to !powerSaveMode
        )
        val otherBasePermissionsMissing = !phoneAllowed || !callPermissionAllowed || !notificationsAllowed ||
            !microphoneAllowed || !fineAllowed
        val permissionsToRequest = buildList {
            if (!microphoneAllowed) add(Manifest.permission.RECORD_AUDIO)
            if (!notificationsAllowed) add(Manifest.permission.POST_NOTIFICATIONS)
            if (!phoneAllowed) add(Manifest.permission.READ_PHONE_STATE)
            if (!callPermissionAllowed) add(Manifest.permission.CALL_PHONE)
            if (!fineAllowed) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.toTypedArray()
        val openAppSettings = {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }
        val openOverlaySettings = {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: android.content.ActivityNotFoundException) {
                message = "Dit toestel biedt geen instelling voor weergave boven andere apps."
            }
        }
        val openFullScreenSettings = {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } catch (_: android.content.ActivityNotFoundException) {
                openAppSettings()
            }
        }
        val openExactAlarmSettings = {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } catch (_: android.content.ActivityNotFoundException) {
                message = "Dit toestel biedt geen instelling voor exacte alarmen."
            }
        }
        val requestBatteryOptimizationExemption = {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
        val openBatterySaverSettings = {
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }

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
                        if (!overlayAllowed) {
                            Text("Sta weergave boven andere apps toe, zodat de rode SOS-rand zichtbaar blijft.")
                            OutlinedButton(onClick = openOverlaySettings, modifier = Modifier.fillMaxWidth()) {
                                Text("Sta weergave boven andere apps toe")
                            }
                        }
                        if (!fullScreenAllowed) {
                            Text("Sta schermvullende meldingen toe, zodat je een alarm ook op het vergrendelscherm kunt stoppen.")
                            OutlinedButton(onClick = openFullScreenSettings, modifier = Modifier.fillMaxWidth()) {
                                Text("Sta alarmscherm toe")
                            }
                        }
                        if (!exactAlarmsAllowed) {
                            Text("Sta exacte alarmen toe, zodat Android de belpogingen niet uitstelt.")
                            OutlinedButton(onClick = openExactAlarmSettings, modifier = Modifier.fillMaxWidth()) {
                                Text("Sta exacte alarmen toe")
                            }
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
                        Text(if (singleSimId != null) "Noodcontacten" else "Contacten en SIM",
                            fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(if (singleSimId != null) "Voeg een bereikbaar noodcontact toe."
                            else "Voeg een bereikbaar noodcontact toe en kies de SIM voor alarm-SMS.")
                        EmergencyContacts(contacts, onChange = {
                            contacts = it
                            settings.contacts = it
                        }, onAdd = pickContact)
                        if (subscriptions.size != 1) {
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
                        Text("Controle", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(if (singleSimId != null) "Machtigingen en contact zijn ingesteld."
                            else "Machtigingen, contact en SIM zijn ingesteld.")
                        if (message.isNotBlank()) Text(message)
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
        val callReady = PhoneCallAction.canStart(this@MainActivity, settings)
        val voiceDetectionOn = listeningState == ListeningState.LISTENING ||
            listeningState == ListeningState.STARTING || listeningState == ListeningState.PAUSED
        val startManualSos: () -> Unit = {
            val started = AlertRuntime.startManual(this@MainActivity, AlertReason.SOS)
            sosModeActive = settings.sosModeActive
            activeState = AlertRuntime.state()
            message = when {
                !started -> "Alarm niet gestart: controleer SMS- en belinstellingen onder Alarm."
                activeState == AlertState.ERROR -> "SOS gestart, maar een alarmactie mislukte. Controleer de instellingen."
                !smsEnabled -> "SOS gestart; SMS staat uit en er wordt geen bericht verzonden."
                else -> "SOS gestart; alarmberichten worden direct verstuurd."
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize().border(
                if (sosModeActive) 8.dp else 0.dp, Color(0xFFFF1010)
            ),
            bottomBar = {
                NavigationBar {
                    val tabs = listOf("Start", "Bewaking", "Alarm", "Contacten", "Instellingen")
                    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.Mic,
                        Icons.Outlined.Bolt, Icons.Outlined.Contacts, Icons.Outlined.Settings)
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(icons[index], contentDescription = null, modifier = Modifier.size(26.dp)) },
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
                            HoldToConfirmSos(
                                countdown = activeState == AlertState.COUNTDOWN,
                                sosModeActive = sosModeActive,
                                remainingSeconds = remaining,
                                onTrigger = startManualSos,
                                onCancel = {
                                    AlertRuntime.cancel(this@MainActivity)
                                    sosModeActive = settings.sosModeActive
                                    activeState = AlertRuntime.state()
                                }
                            )
                            if (sosModeActive) {
                                ElevatedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("Hulp wordt gewaarschuwd", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                        if (smsEnabled) BigStatusLine(smsStatus.ifBlank { "SMS wordt verstuurd…" },
                                            !smsStatus.contains("mislukt"))
                                        if (callEnabled && callRoundStatus.isNotBlank()) BigStatusLine(callRoundStatus,
                                            !callRoundStatus.contains("mislukt"))
                                        if (!overlayAllowed) OutlinedButton(onClick = openOverlaySettings) {
                                            Text("Sta weergave boven andere apps toe")
                                        }
                                    }
                                }
                            } else if (activeState != AlertState.COUNTDOWN) {
                                val actionReady = (smsEnabled && smsReady) || (callEnabled && callReady)
                                val problems = buildList {
                                    if (!actionReady) add("Er kan niemand gewaarschuwd worden. Vraag hulp bij het instellen.")
                                    if (listeningState == ListeningState.PAUSED || listeningState == ListeningState.ERROR)
                                        add("Luisteren naar een hulproep werkt nu niet.")
                                    if (fallDetectionOn && !fallServiceActive) add("Valdetectie werkt nu niet.")
                                    if (!allSetupRequirementsMet) add("e-SOS mist toestemmingen. Kijk bij Instellingen.")
                                }
                                ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (problems.isEmpty()) Color(0xFFE3F2E6) else Color(0xFFFBE4E2))) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(if (problems.isEmpty()) "✓ Je bent beschermd" else "✕ Let op",
                                            fontSize = 28.sp, fontWeight = FontWeight.Bold,
                                            color = if (problems.isEmpty()) Color(0xFF1B6E2E) else Color(0xFF9D2727))
                                        problems.forEach { BigStatusLine(it, false) }
                                    }
                                }
                                val watching = buildList {
                                    if (listeningState == ListeningState.LISTENING) add("Luistert naar een hulproep")
                                    if (fallDetectionOn && fallServiceActive) add("Merkt het als je valt")
                                    if (inactivityEnabled) add("Waarschuwt na $inactivityHours uur zonder gebruik")
                                }
                                Text("Wat e-SOS doet", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                if (watching.isEmpty()) BigStatusLine("Alleen de SOS-knop hierboven", true)
                                watching.forEach { BigStatusLine(it, true) }
                                Text("Wie wordt gewaarschuwd", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                if (contacts.isEmpty()) BigStatusLine("Nog niemand", false)
                                contacts.forEach { contact ->
                                    BigStatusLine(contact.name + when {
                                        smsEnabled && callEnabled -> ": SMS en telefoon"
                                        smsEnabled -> ": SMS"
                                        callEnabled -> ": telefoon"
                                        else -> ""
                                    }, actionReady)
                                }
                            }
                            if (message.isNotBlank()) Text(message)
                        }
                        1 -> {
                            Text("Bewaking", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Waarschuwen als ik de telefoon niet gebruik", fontWeight = FontWeight.SemiBold)
                                    Text("Als je de telefoon ontgrendelt, weet e-SOS dat het goed gaat.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = inactivityEnabled, onCheckedChange = { enabled ->
                                    if (enabled && !notificationsAllowed) {
                                        message = "Geef eerst meldingentoegang via Instellingen voor de blijvende bewaking."
                                    } else {
                                        try {
                                            InactivityMonitor.setEnabled(this@MainActivity, enabled)
                                            inactivityEnabled = enabled
                                            message = ""
                                        } catch (_: RuntimeException) {
                                            if (enabled) runCatching {
                                                InactivityMonitor.setEnabled(this@MainActivity, false)
                                            }
                                            inactivityEnabled = false
                                            message = "Inactiviteitsbewaking kon niet starten; controleer de meldingsmachtiging."
                                        }
                                    }
                                })
                            }
                            if (inactivityEnabled) {
                                Column(Modifier.fillMaxWidth().padding(start = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("SOS na $inactivityHours uur zonder ontgrendeling",
                                        style = MaterialTheme.typography.bodySmall)
                                    Slider(value = inactivityHours.toFloat(), onValueChange = { value ->
                                        inactivityHours = value.toInt().coerceIn(1, 72)
                                        settings.inactivityHours = inactivityHours
                                    }, onValueChangeFinished = {
                                        InactivityMonitor.schedule(this@MainActivity)
                                    }, valueRange = 1f..72f, steps = 70)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("1 uur", style = MaterialTheme.typography.bodySmall)
                                        Text("72 uur", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("Slaaptijd (niet meetellen)", fontWeight = FontWeight.SemiBold)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            TimePickerDialog(this@MainActivity, { _, hour, minute ->
                                                inactivitySleepStartMinute = hour * 60 + minute
                                                settings.inactivitySleepStartMinute = inactivitySleepStartMinute
                                                InactivityMonitor.schedule(this@MainActivity)
                                            }, inactivitySleepStartMinute / 60, inactivitySleepStartMinute % 60, true).show()
                                        }, modifier = Modifier.weight(1f)) {
                                            Text("Vanaf ${formatClockTime(inactivitySleepStartMinute)}")
                                        }
                                        OutlinedButton(onClick = {
                                            TimePickerDialog(this@MainActivity, { _, hour, minute ->
                                                inactivitySleepEndMinute = hour * 60 + minute
                                                settings.inactivitySleepEndMinute = inactivitySleepEndMinute
                                                InactivityMonitor.schedule(this@MainActivity)
                                            }, inactivitySleepEndMinute / 60, inactivitySleepEndMinute % 60, true).show()
                                        }, modifier = Modifier.weight(1f)) {
                                            Text("Tot ${formatClockTime(inactivitySleepEndMinute)}")
                                        }
                                    }
                                    Text(if (inactivitySleepStartMinute == inactivitySleepEndMinute)
                                        "Slaaptijd is uitgeschakeld."
                                    else "Deze uren tellen niet mee voor de inactiviteitsduur.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Luisteren naar een hulproep", fontWeight = FontWeight.SemiBold)
                                    Text("Zeg een hulpzin om alarm te geven. Alles blijft op de telefoon.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = voiceDetectionOn, onCheckedChange = { enabled ->
                                    if (!enabled) {
                                        startService(Intent(this@MainActivity, ListeningService::class.java)
                                            .setAction(ListeningService.STOP))
                                    } else if (!microphoneAllowed || !notificationsAllowed ||
                                        (smsEnabled && !smsReady) || (callEnabled && !callReady)
                                    ) {
                                        message = when {
                                            !microphoneAllowed -> "Geef eerst microfoontoegang via Instellingen."
                                            !notificationsAllowed -> "Geef eerst meldingentoegang via Instellingen."
                                            callEnabled && !callReady -> "Controleer de belmachtiging en het eerste contact."
                                            else -> "Stel eerst SMS-contact, SIM en machtigingen in via Alarm."
                                        }
                                    } else {
                                        try {
                                            ContextCompat.startForegroundService(this@MainActivity,
                                                Intent(this@MainActivity, ListeningService::class.java)
                                                    .setAction(ListeningService.START))
                                            message = ""
                                        } catch (_: RuntimeException) {
                                            message = "Spraakdetectie kon niet starten; controleer de machtigingen."
                                        }
                                    }
                                })
                            }
                            if (voiceDetectionOn) {
                                Column(Modifier.fillMaxWidth().padding(start = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PhraseEditor("Hulpzinnen", "hulpzin", wakePhrases, onChange = {
                                        wakePhrases = it
                                        settings.wakePhrases = it
                                    }, onMessage = { message = it })
                                    PhraseEditor("Zinnen om het alarm te stoppen", "stopzin", cancelPhrases, onChange = {
                                        cancelPhrases = it
                                        settings.cancelPhrases = it
                                    }, onMessage = { message = it })
                                }
                            }
                            if (!microphoneAllowed) OutlinedButton(onClick = {
                                permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            }) { Text("Vraag microfoontoegang") }
                            if (smsEnabled && !smsReady) Text("De SMS-actie is nog niet ingesteld. Stel deze in onder Alarm om het luisteren te starten.")
                            if (callEnabled && !callReady) Text("De belactie is nog niet gereed. Controleer de belmachtiging en het eerste contact.")
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Valdetectie", fontWeight = FontWeight.SemiBold)
                                    Text("Merkt het als je valt en daarna stil blijft liggen.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = fallDetectionOn, onCheckedChange = { enabled ->
                                    if (!enabled) {
                                        fallDetectionOn = false
                                        settings.fallDetectionEnabled = false
                                        FallDetectionService.stop(this@MainActivity)
                                    } else {
                                        val requestedPermissions = buildList {
                                            if (!activityRecognitionAllowed) add(Manifest.permission.ACTIVITY_RECOGNITION)
                                            if (!notificationsAllowed) add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        if (requestedPermissions.isEmpty()) enableFallDetection()
                                        else fallPermissions.launch(requestedPermissions.toTypedArray())
                                    }
                                })
                            }
                            if (fallDetectionOn) {
                                Text("Gevoeligheid: $fallSensitivity / 5", fontWeight = FontWeight.SemiBold)
                                Slider(value = fallSensitivity.toFloat(), onValueChange = { value ->
                                    fallSensitivity = value.toInt()
                                    settings.fallSensitivity = fallSensitivity
                                }, valueRange = 1f..5f, steps = 3)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Minder gevoelig", style = MaterialTheme.typography.bodySmall)
                                    Text("Meer gevoelig", style = MaterialTheme.typography.bodySmall)
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
                            if (message.isNotBlank()) Text(message)
                        }
                        2 -> {
                            Text("Bij een alarm", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("Kies wat e-SOS doet als er een alarm is.")
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Alarmgeluid afspelen", fontWeight = FontWeight.SemiBold)
                                    Text(if (alarmSoundEnabled) "Tijdens SOS klinkt een luid signaal over de speaker, op maximaal alarmvolume."
                                        else "Er klinkt geen alarmgeluid.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = alarmSoundEnabled, onCheckedChange = {
                                    alarmSoundEnabled = it
                                    settings.alarmSoundEnabled = it
                                    if (!it && !sosModeActive) SosSignals.stop(this@MainActivity)
                                })
                            }
                            if (alarmSoundEnabled) {
                                Column(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                                    listOf(AlarmSoundMode.HIGH_TONE to "Hoge, luide pieptoon",
                                        AlarmSoundMode.MORSE_SOS to "Luide pieptoon in morse: SOS").forEach { (mode, label) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = alarmSoundMode == mode, onClick = {
                                                alarmSoundMode = mode
                                                settings.alarmSoundMode = mode
                                            })
                                            Text(label)
                                        }
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Noodcontacten bellen", fontWeight = FontWeight.SemiBold)
                                    Text(if (callEnabled) "Het eerste contact wordt direct gebeld; daarna elke 15 minuten het volgende, zolang SOS actief is."
                                        else "Er wordt niet gebeld.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = callEnabled, onCheckedChange = { enabled ->
                                    when {
                                        !enabled -> {
                                            callEnabled = false
                                            settings.callEnabled = false
                                            SosCallScheduler.cancel(this@MainActivity)
                                            message = "Belactie uitgeschakeld."
                                        }
                                        !callPermissionAllowed ->
                                            permissions.launch(arrayOf(Manifest.permission.CALL_PHONE))
                                        else -> {
                                            callEnabled = true
                                            settings.callEnabled = true
                                            message = "Belactie ingeschakeld."
                                        }
                                    }
                                })
                            }
                            if (callEnabled && !callReady) Text("Bellen vereist de belmachtiging en een noodcontact met een geldig nummer.")
                            if (callEnabled && !exactAlarmsAllowed) {
                                Text("Zonder exacte alarmen kan Android de volgende belpoging uitstellen.")
                                OutlinedButton(onClick = openExactAlarmSettings) { Text("Sta exacte alarmen toe") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Gesproken uitleg", fontWeight = FontWeight.SemiBold)
                                    Text(if (spokenFeedbackEnabled) "e-SOS spreekt meldingen uit, zoals het begin en einde van de noodprocedure."
                                        else "e-SOS blijft stil; alleen alarmgeluiden klinken.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = spokenFeedbackEnabled, onCheckedChange = {
                                    spokenFeedbackEnabled = it
                                    settings.spokenFeedbackEnabled = it
                                })
                            }
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
                                Column(Modifier.fillMaxWidth().padding(start = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    HorizontalDivider()
                                    if (subscriptions.size != 1) {
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
                                    }
                                    if (!smsAllowed) {
                                        Text("SMS-toestemming is nodig om berichten te versturen.", color = Color(0xFFC62828))
                                        OutlinedButton(onClick = {
                                            permissions.launch(arrayOf(Manifest.permission.SEND_SMS))
                                        }) { Text("Vraag SMS-toestemming") }
                                    }
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
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Trillen", fontWeight = FontWeight.SemiBold)
                                    Text(if (vibrationEnabled) "De telefoon trilt het patroon ··· −−− ···, zie de opties hieronder."
                                        else "De telefoon trilt niet.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = vibrationEnabled, onCheckedChange = {
                                    vibrationEnabled = it
                                    settings.vibrationEnabled = it
                                    if (!it && !sosModeActive) SosSignals.stop(this@MainActivity)
                                })
                            }
                            if (vibrationEnabled) {
                                Column(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                                    listOf(false to "Eenmalig bij start van de countdown", true to "Continu zodra SOS echt is getriggerd, tot annuleren").forEach { (continuous, label) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = vibrationContinuous == continuous, onClick = {
                                                vibrationContinuous = continuous
                                                settings.vibrationContinuous = continuous
                                            })
                                            Text(label)
                                        }
                                    }
                                }
                            }
                            if (message.isNotBlank()) Text(message)
                        }
                        3 -> {
                            Text("Noodcontacten", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            EmergencyContacts(contacts, onChange = {
                                contacts = it
                                settings.contacts = it
                            }, onAdd = pickContact)
                            if (message.isNotBlank()) Text(message)
                        }
                        else -> {
                            Text("Instellingen", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("Machtigingen", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            permissionStatuses.forEach { (label, granted) -> PermissionStatusRow(label, granted) }
                            if (!overlayAllowed) OutlinedButton(onClick = openOverlaySettings,
                                modifier = Modifier.fillMaxWidth()) { Text("Sta weergave boven andere apps toe") }
                            if (!fullScreenAllowed) OutlinedButton(onClick = openFullScreenSettings,
                                modifier = Modifier.fillMaxWidth()) { Text("Sta alarmscherm toe") }
                            if (!exactAlarmsAllowed) OutlinedButton(onClick = openExactAlarmSettings,
                                modifier = Modifier.fillMaxWidth()) { Text("Sta exacte alarmen toe") }
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
            Text(if (active) "✓" else "✕", color = if (active) Color(0xFF218739) else Color(0xFF9D2727),
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(label)
            Spacer(Modifier.width(12.dp))
            Text(value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun BigStatusLine(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(if (ok) "✓" else "✕", color = if (ok) Color(0xFF1B6E2E) else Color(0xFF9D2727),
            fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
        Text(text, fontSize = 20.sp, lineHeight = 28.sp, modifier = Modifier.weight(1f))
    }
}

/** Grotere standaardletters voor oudere gebruikers. */
private fun seniorTypography(): Typography {
    val base = Typography()
    return base.copy(
        bodySmall = base.bodySmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 19.sp, lineHeight = 27.sp),
        titleMedium = base.titleMedium.copy(fontSize = 21.sp, lineHeight = 28.sp),
        labelLarge = base.labelLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
        labelMedium = base.labelMedium.copy(fontSize = 13.sp, lineHeight = 16.sp)
    )
}

private fun formatClockTime(minutesSinceMidnight: Int): String {
    val hours = minutesSinceMidnight / 60
    val minutes = minutesSinceMidnight % 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}

@Composable
private fun HoldToConfirmSos(
    countdown: Boolean,
    sosModeActive: Boolean,
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

    if (countdown || sosModeActive) {
        if (countdown) Text("SOS-alarm over $remainingSeconds seconden", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        else Text("SOS-modus actief", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(144.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA8332A))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Annuleer SOS", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("Tik om te annuleren")
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().height(144.dp)
                .background(Color(0xFFA8332A), RoundedCornerShape(8.dp))
                .semantics {
                    role = Role.Button
                    onClick(label = "SOS starten") {
                        currentOnTrigger()
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        val heldForThreeSeconds = withTimeoutOrNull(3_000L) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: return@withTimeoutOrNull false
                                if (!change.pressed || change.position.x !in 0f..size.width.toFloat() ||
                                    change.position.y !in 0f..size.height.toFloat()
                                ) return@withTimeoutOrNull false
                            }
                            false
                        } ?: true
                        isPressed = false
                        if (heldForThreeSeconds) currentOnTrigger()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.fillMaxHeight().fillMaxWidth(progress.value)
                    .background(Color(0xFFD94A45), RoundedCornerShape(8.dp))
                    .align(Alignment.CenterStart)
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("SOS", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(if (isPressed) "Vasthouden: ${(3 - (progress.value * 3).toInt()).coerceIn(1, 3)} s" else "3 seconden ingedrukt houden")
            }
        }
    }
}