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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmergencyContacts(
    contacts: List<FamilyContact>,
    onChange: (List<FamilyContact>) -> Unit,
    onAdd: () -> Unit
) {
    val currentContacts by rememberUpdatedState(contacts)
    val currentOnChange by rememberUpdatedState(onChange)
    val rowBounds = remember { mutableMapOf<String, Rect>() }
    var draggedNumber by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        contacts.forEach { contact ->
            key(contact.number) {
                var handleTop by remember { mutableFloatStateOf(0f) }
                Row(Modifier.fillMaxWidth().height(88.dp)
                    .onGloballyPositioned { rowBounds[contact.number] = it.boundsInRoot() }
                    .background(if (draggedNumber == contact.number) MaterialTheme.colorScheme.secondaryContainer
                        else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Contact verplaatsen") } },
                        state = rememberTooltipState()) {
                        Box(Modifier.size(48.dp)
                            .onGloballyPositioned { handleTop = it.boundsInRoot().top }
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction("Omhoog verplaatsen") {
                                        val from = currentContacts.indexOfFirst { it.number == contact.number }
                                        if (from > 0) {
                                            currentOnChange(moveEmergencyContact(currentContacts, from, from - 1))
                                            true
                                        } else false
                                    },
                                    CustomAccessibilityAction("Omlaag verplaatsen") {
                                        val from = currentContacts.indexOfFirst { it.number == contact.number }
                                        if (from >= 0 && from < currentContacts.lastIndex) {
                                            currentOnChange(moveEmergencyContact(currentContacts, from, from + 1))
                                            true
                                        } else false
                                    }
                                )
                            }
                            .pointerInput(contact.number) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        draggedNumber = contact.number
                                        dragY = handleTop + offset.y
                                    },
                                    onDragEnd = { draggedNumber = null },
                                    onDragCancel = { draggedNumber = null },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragY += amount.y
                                        val list = currentContacts
                                        val from = list.indexOfFirst { it.number == contact.number }
                                        if (from >= 0) {
                                            val to = list.indices.minByOrNull { candidate ->
                                                rowBounds[list[candidate].number]?.let { bounds ->
                                                    kotlin.math.abs(bounds.center.y - dragY)
                                                } ?: Float.MAX_VALUE
                                            } ?: from
                                            if (from != to) currentOnChange(moveEmergencyContact(list, from, to))
                                        }
                                    }
                                )
                            }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.DragHandle, contentDescription = "Verplaats ${contact.name}")
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(contact.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold)
                        Text(contact.number, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Contact verwijderen") } },
                        state = rememberTooltipState()) {
                        IconButton(onClick = { currentOnChange(currentContacts - contact) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Verwijder ${contact.name}")
                        }
                    }
                }
            }
        }
        TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Contact toevoegen") } }, state = rememberTooltipState()) {
            FilledIconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = "Contact toevoegen uit contactenlijst")
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private var permissionVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CheckInScheduler.clear(this)
        InactivityMonitorService.startIfEnabled(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF005E55),
            surface = Color(0xFFF9FAF7), onSurface = Color(0xFF182524))) { Home() } }
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
        var manualSosTestVisible by remember { mutableStateOf(false) }
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
                        val normalized = android.telephony.PhoneNumberUtils.normalizeNumber(phone.orEmpty())
                        when {
                            !SmsAlertSender.validNumber(normalized) ->
                                message = "Sla in de contactenlijst een internationaal nummer op (+ en 8-15 cijfers)."
                            contacts.any { it.number == normalized } ->
                                message = "Dit telefoonnummer is al opgeslagen."
                            else -> {
                                contacts = contacts + FamilyContact(contactName.orEmpty().ifBlank { normalized }, normalized)
                                settings.contacts = contacts
                                message = "Contact opgeslagen."
                            }
                        }
                    }
                } catch (_: SecurityException) {
                    message = "Geef toegang tot contacten en probeer het opnieuw."
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
        val contactPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRefresh++
            if (granted) pickContact()
            else message = "Contacttoegang is nodig om een contact te selecteren."
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
        val contactsAllowed = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val callPermissionAllowed = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val overlayAllowed = Settings.canDrawOverlays(this)
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
        val openOverlaySettings = {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: android.content.ActivityNotFoundException) {
                message = "Dit toestel biedt geen instelling voor weergave boven andere apps."
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
                        Text("Machtigingen, contact en SIM zijn ingesteld.")
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
                !started -> "Alarm niet gestart: controleer SMS- en belinstellingen onder Acties."
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
                    val tabs = listOf("Dashboard", "Detectie", "Acties", "Contacten", "Instellingen")
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
                            Text("Dashboard", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            if (sosModeActive) {
                                StatusRow("Rode rand boven andere apps",
                                    if (overlayVisible) "Actief" else "Niet actief", overlayVisible)
                                if (!overlayAllowed) OutlinedButton(onClick = openOverlaySettings) {
                                    Text("Sta weergave boven andere apps toe")
                                }
                                if (callEnabled && callRoundStatus.isNotBlank()) Text(callRoundStatus)
                            }
                            if (!(smsEnabled && smsReady) && !(callEnabled && callReady)) {
                                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Outlined.WarningAmber, contentDescription = "Waarschuwing",
                                        tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Text(if (!smsEnabled && !callEnabled)
                                        "Geen alarmacties actief. Bij een alarm wordt niemand gewaarschuwd. Dit is gevaarlijk."
                                        else "Geen alarmacties gereed. Controleer Acties: bij een alarm kan niemand worden gewaarschuwd. Dit is gevaarlijk.",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                }
                            }
                            val protectionTitle = when {
                                sosModeActive -> "SOS-modus actief"
                                listeningState == ListeningState.STARTING -> "Spraakdetectie wordt gestart"
                                listeningState == ListeningState.PAUSED -> "Spraakdetectie heeft aandacht nodig"
                                listeningState == ListeningState.ERROR -> "Spraakdetectie heeft aandacht nodig"
                                listeningState == ListeningState.LISTENING && (inactivityEnabled || fallDetectionOn) -> "SOS-bewaking actief"
                                listeningState == ListeningState.LISTENING -> "Spraakdetectie actief"
                                fallDetectionOn -> "SOS-bewaking actief"
                                inactivityEnabled -> "Inactiviteitsbewaking actief"
                                else -> "Geen actieve SOS-triggers"
                            }
                            val showSpeechStatus = listeningState != ListeningState.STOPPED
                            val hasDetectionStatuses = showSpeechStatus || fallDetectionOn || inactivityEnabled
                            val hasActionStatuses = smsEnabled || callEnabled || alarmSoundEnabled || vibrationEnabled
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text(protectionTitle, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                                        color = when {
                                            listeningState == ListeningState.PAUSED -> Color(0xFF8B5E00)
                                            listeningState == ListeningState.LISTENING || inactivityEnabled || fallDetectionOn -> Color(0xFF005E55)
                                            else -> Color(0xFF9D2727)
                                        })
                                    if (hasDetectionStatuses) {
                                        HorizontalDivider()
                                        Text("Detectie", style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold)
                                        if (inactivityEnabled) StatusRow("Inactiviteitsbewaking",
                                            "Actief ($inactivityHours uur)", true)
                                        if (showSpeechStatus) {
                                            StatusRow("Spraakdetectie", when (listeningState) {
                                                ListeningState.LISTENING -> "Actief"
                                                ListeningState.STARTING -> "Wordt gestart"
                                                ListeningState.PAUSED -> "Gepauzeerd"
                                                ListeningState.ERROR -> "Gestopt door fout"
                                                ListeningState.STOPPED -> "Uit"
                                            }, listeningState == ListeningState.LISTENING)
                                            if (listeningDetail.isNotBlank()) Text(listeningDetail,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (fallDetectionOn) {
                                            StatusRow("Valdetectie", if (fallServiceActive) "Actief" else "Niet actief",
                                                fallServiceActive)
                                            if (fallDetail.isNotBlank()) Text(fallDetail,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (hasActionStatuses) {
                                        if (hasDetectionStatuses) HorizontalDivider()
                                        Text("Acties", style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold)
                                        if (alarmSoundEnabled) StatusRow("Alarmgeluid", when (alarmSoundMode) {
                                            AlarmSoundMode.HIGH_TONE -> "Hoge pieptoon"
                                            AlarmSoundMode.MORSE_SOS -> "SOS in morse"
                                        }, true)
                                        if (callEnabled) StatusRow("Belactie",
                                            if (callReady) "Ingeschakeld" else "Ingeschakeld, niet gereed", callReady)
                                        if (smsEnabled) StatusRow("SMS-actie",
                                            if (smsReady) "Ingeschakeld" else "Ingeschakeld, niet ingesteld", smsReady)
                                        if (vibrationEnabled) StatusRow("Trillen",
                                            if (vibrationContinuous) "SOS in morse, continu" else "SOS in morse, eenmalig bij countdown", true)
                                        if (smsEnabled && shareGps) StatusRow("Locatie delen", "Ingeschakeld", true)
                                    }
                                }
                            }
                        }
                        1 -> {
                            Text("Detectie", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Inactiviteitsbewaking", fontWeight = FontWeight.SemiBold)
                                    Text("Een ontgrendeling van het apparaat geldt als teken van leven.",
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
                                    Text("Spraakdetectie", fontWeight = FontWeight.SemiBold)
                                    Text("Herkent lokaal de ingestelde alarm- en annuleer-zinnen.",
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
                                            else -> "Stel eerst SMS-contact, SIM en machtigingen in via Acties."
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
                                    PhraseEditor("Alarm-zinnen", "alarm-zin", wakePhrases, onChange = {
                                        wakePhrases = it
                                        settings.wakePhrases = it
                                    }, onMessage = { message = it })
                                    PhraseEditor("Annuleer-zinnen", "annuleer-zin", cancelPhrases, onChange = {
                                        cancelPhrases = it
                                        settings.cancelPhrases = it
                                    }, onMessage = { message = it })
                                }
                            }
                            if (!microphoneAllowed) OutlinedButton(onClick = {
                                permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            }) { Text("Vraag microfoontoegang") }
                            if (smsEnabled && !smsReady) Text("De SMS-actie is nog niet ingesteld. Configureer deze onder Acties om spraakdetectie te starten.")
                            if (callEnabled && !callReady) Text("De belactie is nog niet gereed. Controleer de belmachtiging en het eerste contact.")
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Valdetectie", fontWeight = FontWeight.SemiBold)
                                    Text("Detecteert vrije val, impact en daarna stilstand met de versnellingsmeter.",
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
                            if (activeState == AlertState.COUNTDOWN) HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Handmatig", fontWeight = FontWeight.SemiBold)
                                    Text("Toon de knop voor een handmatige SOS-test.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = manualSosTestVisible,
                                    onCheckedChange = { manualSosTestVisible = it })
                            }
                            if (manualSosTestVisible && activeState != AlertState.COUNTDOWN) {
                                OutlinedButton(onClick = {
                                    val started = AlertRuntime.startManualTest(this@MainActivity, AlertReason.SOS)
                                    message = when {
                                        !started -> "SOS-test niet gestart: controleer SMS-instellingen of er is al een alarm actief."
                                        !smsEnabled -> "SOS-test gestart; de SMS-actie staat uit, dus er wordt geen bericht verzonden."
                                        else -> "SOS-test gestart; testberichten worden direct verstuurd."
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Start SOS-test") }
                            }
                            if (message.isNotBlank()) Text(message)
                        }
                        2 -> {
                            Text("Acties", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("Kies welke acties e-SOS bij een alarm uitvoert.")
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
                                    Text("Contacten bellen", fontWeight = FontWeight.SemiBold)
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
                                    Text("Gesproken feedback", fontWeight = FontWeight.SemiBold)
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
                                    Text("Trillen in morse: SOS", fontWeight = FontWeight.SemiBold)
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
                            Text("Contacten", fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
                            PermissionStatusRow("Belmachtiging", callPermissionAllowed)
                            PermissionStatusRow("Weergeven boven andere apps", overlayAllowed)
                            if (!overlayAllowed) OutlinedButton(onClick = openOverlaySettings,
                                modifier = Modifier.fillMaxWidth()) { Text("Sta weergave boven andere apps toe") }
                            PermissionStatusRow("Exacte beltimers", exactAlarmsAllowed)
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
            Text(label)
            Spacer(Modifier.width(12.dp))
            Text(value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f))
        }
    }
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