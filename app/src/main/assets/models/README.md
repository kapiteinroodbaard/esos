# Modelstatus

Vosk NL is gebundeld in deze bronboom. De app heeft een door de gebruiker
te starten offline microfoonservice. Er zijn nog geen resultaten van de
toestel-/datasetproef; herkenning en F-Droid-opname zijn niet vrijgegeven.

| Gebundeld model | Rechtstreekse bron | SHA-256 van ZIP | Cataloguslicentie |
| --- | --- | --- | --- |
| vosk-model-small-nl-0.22.zip | [Alpha Cephei](https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip) | `039811c3b829de64e4f123a9f684a53784005b212a346ac0b899dc7efce2ed0a` | Apache-2.0 |

De [Apache-2.0-licentietekst](LICENSE-APACHE-2.0.txt) staat bij de modellen;
de originele README-bestanden en eventuele notices blijven in de ZIP's.

Controleer met `sha256sum app/src/main/assets/models/*.zip`. De Android-library
`com.alphacephei:vosk-android:0.3.47` is volgens zijn [Maven-POM](https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.pom)
Apache-2.0; deze AAR bevat native `libvosk.so` voor arm64-v8a, armeabi-v7a,
x86 en x86_64 en hangt af van JNA 5.13.0. Verifieer de herkomst en bouwbaarheid
van beide native libraries afzonderlijk voor F-Droid.

Te vergelijken (andere opties niet geselecteerd of gevalideerd):

| Kandidaat | Bron | Vermelde licentie | Werkelijk te toetsen woorden |
| --- | --- | --- | --- |
| Vosk NL 0.22 | [modelcatalogus](https://alphacephei.com/vosk/models) | Apache-2.0 volgens modelcatalogus | help mij; ik heb hulp nodig |
| sherpa-onnx KWS | [KWS-catalogus](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html) | Per gekozen model apart controleren | Engelse hulpfrases |
| openWakeWord bestaande Engelse modellen | [broncode](https://github.com/dscripka/openWakeWord) | code Apache-2.0; meegeleverde modellen CC BY-NC-SA-4.0 | bijvoorbeeld hey mycroft, NIET help help |

Voor vrijgave: upstream-licentietekst, redistributierecht,
native-library-herkomst en F-Droid-beoordeling per asset verifiëren.
Geen live download tijdens build of alarm. NC-assets alleen na
expliciete F-Droid-beoordeling, niet automatisch omdat de app gratis is.
