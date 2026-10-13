# e-SOS (ontwikkelversie, **niet gebruiken als noodvoorziening**)

Native Android 14+ prototype met **experimentele offline spraakactivatie** in
Nederlands en Engels. De app bevat ook een handmatige SOS-knop met 10 s
annuleertijd, een simulatie zonder SMS, een apart bevestigde echte SMS-test,
SIM-keuze, optionele GPS en een dagelijkse check-in met 2 uur respijt.
Vosk-herkenning werkt in de app, maar de prestatie- en batterijcriteria zijn
**niet gevalideerd**: gebruik dit prototype niet als enige noodvoorziening.

## Bouwen

Installeer een JDK 17 en Android SDK Platform 36 / Build Tools 36.0.0. Zet
`JAVA_HOME` en `ANDROID_HOME`; voer `./gradlew testDebugUnitTest lintDebug assembleDebug`
uit voor validatie. Voor een versioned distributie-APK voer je
`./gradlew exportDebugApk` uit; die schrijft `dist/eSOS-v<versionName>.apk`.
De app toont dezelfde SemVer in de sectie "Over e-SOS". Verhoog voor elke
nieuwe APK-build zowel `versionName` (SemVer) als de monotone `versionCode`;
zie [agent.md](agent.md). Gradle 8.14.3 wordt via de wrapper met SHA-256-verificatie opgehaald.
Maven-dependencies worden tijdens de eerste build opgehaald; beide Vosk-modellen
staan als ZIP in de bronboom en worden bij de eerste luisterstart in private
appopslag uitgepakt. Hiervoor is honderden MB vrije ruimte nodig. Er is geen
modeldownload tijdens build of alarm. Er zijn geen Google Play Services, Firebase,
trackers, backend, account of netwerk-API in de app. Debug-APK:
De distributie-APK staat onder `dist/` met het versienummer in de bestandsnaam.
Voer `connectedDebugAndroidTest` alleen op een wegwerp-emulator uit: Gradle kan
de doelapp na de test verwijderen, inclusief app-private instellingen.

`org.esos.app` is een **tijdelijke** application ID. De uitgever moet voor
publicatie een unieke, beheerste ID/namespace en bijbehorende Kotlin-packages
vastleggen. De bron is MIT-gelicentieerd; dependency- en native-librarylicenties
en F-Droid inclusion/buildbeleid moeten vóór uitgifte apart worden nagekeken.
Er is nog geen F-Droid-buildmetadata of inclusion-goedkeuring.

## Spraak testen

Sla familiecontact(en) en SIM op en verleen SMS-, telefoon-, meldings- en
microfoontoegang. Druk in de **zichtbare** app op "Start spraakdetectie" en
wacht na het uitpakken op "Luistert (experimenteel)" plus de doorlopende
microfoonnotificatie. Zeg "help help", "help mij", "ik heb hulp nodig",
"help me" of "I need help"; alleen volledige herkende frases starten de
hoorbare aftelling van 10 s. Annuleer vanuit scherm/notificatie wanneer het
een test is. **Gebruik uitsluitend vooraf afgesproken testcontacten**: ook een
spraaktest kan anders automatisch echte, betaalde SMS'en versturen. De knop
"Test zonder SMS" test alleen de alarmroute, niet de microfoonherkenning.
Met Vosk 0.3.75 heeft de instrumentatietest op een fysieke Android 17 Pixel
(4 KB-pagina's) het uitpakken en laden van beide modellen en verwerking van
stilte geverifieerd. De 64-bit ELF- en APK-uitlijning voldoet aan 16 KB;
uitvoering op een 16 KB-kernel en een uitgesproken "help help" via een echte
microfoon zijn nog niet getest.

Stop via de app of de doorlopende notificatie. Luisteren wordt na reboot of
processtop niet stilzwijgend hervat; controleer de status opnieuw. Een oproep,
gemute microfoon, lege batterij, ontbrekende SIM of uitgevallen audio toont
een niet-beschermdstatus. De app bewaart geen opnames of transcripten.

## Gebruik en privacy

Sla een of meer contacten met internationaal telefoonnummer en een actieve SIM
op; de SOS-tekst wordt na 10 s aan elk nummer aangeboden. De check-in stuurt de aparte
tekst "geen dagelijkse bevestiging ontvangen" wanneer na 2 uur niets is
bevestigd. Het check-in-alarm gebruikt geen microfoon. Een SOS en een GPS-aanvulling
kunnen elk meerdere betaalde SMS-delen kosten. SMS vereist een actieve SIM,
mobiel netwerk en de machtigingen SEND_SMS en READ_PHONE_STATE. "Verzonden" is
niet hetzelfde als "afgeleverd" of "gelezen"; test contact en netwerk expliciet.
De acties **SMS versturen** en **Contacten bellen** zijn onafhankelijk.
**Locatie delen** staat standaard aan en verschijnt als ingesprongen instelling
onder **SMS versturen** wanneer die actie aan staat. Zonder SMS-actie wordt geen
GPS-locatie opgevraagd; een eerder opgeslagen locatiekeuze blijft behouden.
Contacten worden met de plusknop rechtstreeks uit de contactenlijst toegevoegd;
selecteer daar een internationaal telefoonnummer. Met de sleepgreep kunnen contacten
omhoog of omlaag worden verplaatst, ook tijdens de eerste configuratie.
Toevoegen, verwijderen en wijzigen van de volgorde worden direct opgeslagen; SIM-keuze en schakelaars
gelden direct, zonder aparte bewaarknop. Als bellen aan staat, belt een echt alarm
het eerste noodcontact, ook als SMS uit staat of mislukt. Daarna wordt elke
15 minuten het volgende contact geprobeerd, eenmaal door de hele lijst heen.
De volgorde wordt bij het starten van de belronde vastgelegd; wijzigingen gelden
voor het volgende alarm. De SOS-test belt
nooit. Bij een echt alarm klinkt na het aanbieden van de SMS aan alle contacten
de opname `7-sms-verstuurd.mp3`; dit bevestigt geen aflevering. Een mislukte
SMS-aanbieding krijgt geen gesproken succesmelding. Voor het bellen klinkt
`8-bellen-contact.mp3` volledig; daarna wordt het contact gebeld. Annuleren
tijdens deze opnames voorkomt de uitgestelde oproep. Bellen vereist `CALL_PHONE`, maar e-SOS hoeft niet de standaard telefoon-app
te worden. De bestaande telefoon-app verzorgt het gesprek en de audioroute;
e-SOS geeft beschikbare Bluetooth-gespreksapparaten voorrang en vraagt anders
speakerphone aan, maar kan de uiteindelijke audioroute, automatische ophanging
en audio-instructies tijdens het gesprek niet afdwingen zonder telefoonapp-rol.
Er wordt nooit automatisch een noodnummer gekozen.

e-SOS vraagt geen standaard-telefoonapp-rol aan. Zonder die rol kan de app niet
betrouwbaar vaststellen of een uitgaande oproep is opgenomen of deze automatisch
verbreken. Ophangen na 5 seconden gesprek of 10 seconden overgaan en herhalen na
5 minuten zonder antwoord zijn **niet beschikbaar**. De gebruiker beheert het
gesprek in de bestaande telefoon-app. De volgende belpoging wordt 15 minuten na
de vorige poging gepland, ongeacht beantwoording; een nog lopend gesprek kan de
nieuwe oproep blokkeren. Een door Android geaccepteerd belverzoek bewijst niet
dat het contact bereikt is. Androids gewone telefoonstatus maakt geen betrouwbaar onderscheid
tussen uitgaand bellen en een opgenomen gesprek.
De timer en contactvolgorde blijven in app-private opslag bewaard en worden na
reboot herpland zolang SOS en de belactie actief zijn. Annuleren van SOS of
uitschakelen van de belactie verwijdert alle toekomstige oproepen. Zonder
toestemming **Exacte beltimers** kan Android de wachttijd verlengen. Bij verlies
van het proces precies rond een belpoging kan die poging worden overgeslagen;
het volgende contact blijft gepland. Er is geen externe beldienst.
Het dashboard waarschuwt wanneer geen alarmactie is ingeschakeld of gereed is.

De rode SOS-rand kan ook boven het Android-startscherm en gewone apps verschijnen.
Verleen in **Instellingen** de speciale toestemming **Weergeven boven andere apps**.
Vier smalle, niet-aanraakbare rode overlays laten de rest van het scherm vrij;
een voorgronddienst houdt ze actief totdat SOS wordt geannuleerd. Zonder toestemming
blijft alleen de rode rand binnen e-SOS beschikbaar. Android kan overlays verbergen
op beveiligde apps, toestemmingsschermen, systeemschermen en het vergrendelscherm;
een rand boven werkelijk ieder scherm is niet afdwingbaar. Na procesverlies probeert
Android de randdienst te herstellen; na reboot of verleende toestemming wordt de
rand hersteld bij herstel van SOS of het openen van e-SOS. Controleer de dashboardstatus.

`SEND_SMS` is op Android een hard beperkt recht. De installer moet het voor de
app toestaan; daarna moet de gebruiker het recht nog zelf verlenen. Bij een direct
geinstalleerde APK kan Android de SMS-schakelaar blokkeren. Open dan
**Instellingen > Apps > e-SOS > menu rechtsboven > Beperkte instellingen toestaan**
als die optie wordt aangeboden, keer terug naar e-SOS en druk op
**Vraag SMS-toestemming**. Doe dit alleen als je de herkomst van de app vertrouwt.
Als de installer of het toestel SMS blijft blokkeren, werkt automatisch alarmeren
niet; apparaatbeheer of een in-app schakelaar kan dit recht niet omzeilen.
Installeer voor uitgifte via een vertrouwde installer die beperkte rechten
toelaat, en controleer dit per Android-versie en installer met een echte,
vooraf afgesproken SMS-test. Een SMS-app openen ter bevestiging is geen
vervanging voor een onbewaakt SOS-alarm.

Contact, SIM, adres en check-in-instellingen blijven in app-private preferences;
back-up en toesteloverdracht zijn voor deze data uitgeschakeld. Deze opslag is
**niet aanvullend versleuteld**. SMS-tekst, huisadres en eventuele GPS-coordinaten
gaan via de telefoonprovider naar het opgegeven contact; de kaartlink is alleen
tekst in de SMS. Er wordt geen audio of transcript opgenomen, opgeslagen of
verzonden. Incidentlocatie wordt niet permanent opgeslagen. De simulatie doet
geen GPS-aanvraag en stuurt geen SMS. De test-SMS vraagt extra bevestiging.

Locatie is opt-in: aparte precieze en achtergrondlocatietoestemming zijn nodig,
evenals ingeschakelde systeemlocatie/GPS. Alleen een recente GPS-fix (maximaal
30 s oud, maximaal 100 m nauwkeurigheid) kan worden meegestuurd. Een eerste SMS
wacht niet op GPS. Een geschikte fix tot 20 s erna kan eenmaal als aanvulling
worden verstuurd. Binnenshuis is een fix vaak onmogelijk; toestemming weigeren
mag de basis-SMS niet blokkeren.

Android kan alarms in Doze uitstellen. Een processtop of het bewust uitzetten
van de app kan een lopende aftelling of check-in onderbreken; de opgeslagen
deadline en een systeemalarm vormen slechts een best-effort herstelpad.
Na reboot worden check-in-alarmen na de eerste ontgrendeling herpland; er wordt
**geen microfoon herstart**. SMS kan mislukken of dubbel aankomen bij procesverlies
rond de verzending. Gebruik daarnaast een bewezen noodoproepvoorziening.

## Vrijgaveblokkades

- Vergelijk de nu ingebouwde Vosk NL/EN met sherpa-onnx Engels en een *bestaand*
  openWakeWord-woord via afgeschermde, toestemmingsgebonden metingen. Zie
  [modelstatus](app/src/main/assets/models/README.md). De getoonde woorden
  zijn kandidaatfrases, geen bevestigde herkenningsdekking.
- Leg vooraf vast: minstens 90% herkenning binnen 2 m op afgesproken
  geluidsniveau, hoogstens 1 ongewenst alarm na annulering per 24 uur negatieve
  audio en minstens 24 uur op de beoogde batterij. Meet zacht/gerekt roepen,
  aangrenzende kamers, tv/muziek, latency, RAM, stroom en oproepen/mic-conflict
  op Android 14/15/16. Geen vrijgave zonder aanvaarde dekking in beide talen of
  een expliciet als alleen Engels gelabelde variant.
- Voer fysieke instrumentatietests uit voor dual-SIM, GPS aan/uit/geen fix/late
  fix, achtergrondtoestemming, scherm uit, reboot voor/na unlock, Doze,
  notificaties uit, tijdzone/DST, processtop en check-in zonder mic. Echte
  SMS/oproepen uitsluitend met afzonderlijke toestemming naar testcontacten.
  Op een fysiek toestel is alleen de Vosk-stiltetest uitgevoerd; de overige
  controles zijn **niet** uitgevoerd. Best-effort SMS-resultaatmeldingen en uitgestelde
  alarmen voldoen nog niet aan alle vereisten uit [plan](plan.md).
- Controleer bron-, model-, dependency- en native licenties en F-Droid inclusion;
  openWakeWord CC BY-NC-SA-modellen vereisen een aparte expliciete beoordeling.
  Publiceer geen veiligheidsclaim of release voor een geslaagde go/no-go.
