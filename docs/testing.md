# Testbericht und verbleibende Prüfungen

Stand: 20.11.2026. Plattform: Linux x86_64, Java 21.0.13, Minecraft 1.21.1, NeoForge 21.1.253 (zuvor auch 21.1.267), ModDevGradle 2.0.148, Gradle Wrapper 9.2.1, Bun 1.4.4. Keine KittyContracts-Nutzung; Codeerkundung und Abschlussaudit mit ctk.

## Nachweislich geprüft

| Prüfung | Ergebnis |
|---|---|
| `./gradlew clean build` | Erfolgreich; tatsächliche JAR (`build/libs/qb2-0.1.0.jar`) und Liefer-ZIP (`build/distributions/qb2-0.1.0-bundle.zip`) erzeugt |
| JUnit | 69 Tests, 0 Fehler: 25 `CoreTest`, 11 `NetworkTest`, 10 `FlightAndVisualTest`, 4 `FlightRecoveryTest`, 8 `KnowledgeInventoryTest`, 1 `BackgroundBoundaryTest`, 3 `FallbackAndBackoffTest`, 3 `VoxelPathfinderTest`, 4 `ContainerSummaryAndLifecycleTest` |
| Python-Katalogimport | 3 Tests erfolgreich: Batch-IDs, Ablehnung privater Episoden vor Netzwerkzugriff, keine authentifizierten Redirects |
| Dotenv-Parser: `python3 -m unittest discover -s scripts -p 'test_live_configuration.py'` | 4 Tests erfolgreich; keine Shellausführung, keine Werte in Fehlerausgaben |
| `python3 scripts/nerve-smoke.py` | Reales Rust/Bun-NERVE: Schreiben, Retry-Idempotenz, 1000-Keyword-Suche, Welt-/Katalogisolation, authentifizierte Batches, Ablehnung gemischter ungültiger Batches, Eingabeprüfung, `/delete`-Tombstones inkl. Cross-Scope-Schutz (403) und Neustartpersistenz |
| `bun test bridge/article-ids.test.ts` | 7 Tests / 18 Assertions: Journal-Recovery ohne Checkpoint, Retry-Idempotenz, Kompaktion, torn-tail-Recovery, Konflikte, IO-Fail-Closed, Checkpoint-Validierung |
| `bun test test/wal_recovery_test.ts` im NERVE-Repo | 3 Regressionstests mit 15 Assertions erfolgreich: unaligned Frames, 50.000 größere Texte, 120.000 Artikel über mehreren Replay-Batches; ausschließlich isolierte Datenbanken |
| `python3 scripts/client-smoke.py` | Echter Minecraft-/NeoForge-Client, Wegwerfwelt, reale Sichtproben, Inventar-/Container- und Recovery-Checks (`QB2_QA_FINISHED failures=0`), lokale direkte Chatansprache, kontrollierter Google-Protokoll-Modellserver mit validiertem Go-to (Abstand 0,02 Block), reale NERVE-Speicherung und anschließendes Recovery (3.813 Katalogfakten) |
| `python3 scripts/client-smoke.py --craftoria ...` | Isolierte Craftoria-1.37.0-Instanz (527 Mods): 164.366 Katalogfakten importiert, in `bridge/data/` persistiert und nach kaltem Neustart gegen Export verifiziert; Inventar, Geometrie-Recovery, Flug, Go-to (0,06 Block), Rückkehr, Radiusgrenze und Blasen ohne Fehler |
| `python3 scripts/client-smoke.py --live` / `live-test.py` | Frühere Live-Durchläufe mit echtem Google-/Gemma-Aufruf über `.env` bestanden (Antwort, Validierung, NERVE-Persistenz); die abschließenden Live-Läufe scheiterten extern an Google HTTP 503 bzw. Transport-Timeout |
| Clientdarstellung und Flug | Dunkler Viertelblock-Kubus mit vier weißblauen Sensorflächen; freier Flug, Go-to über einem Block, Rückkehr, Gedanken-/Sprechblasen und maskierte Einstellungen in echten Client-Screenshots geprüft |
| JAR-Prüfung | NeoForge-Metadaten, Zielversion, Modklasse, Textur und Sprachressourcen vorhanden; keine Secret-Dateien oder Testklassen enthalten |
| Installer | Kopiert in temporäre Instanz; zweiter Versuch verweigert Überschreiben und erhält die vorhandene JAR unverändert |
| ctk audit | Keine Importzyklen |

Der Clienttest speichert tatsächlich Episoden der Arten Gespräch, Beobachtung, Gedanke und Sprache. Ohne `--live` stammen die Antworten ausdrücklich vom HTTP-Fixture. Mit `--live` wurde Google **real** aufgerufen: Gemma erzeugte eine deutschsprachige Antwort und die Aktion `inspect`, der Client validierte und verarbeitete diese, anschließend wurden die Gedächtniseinträge aus der neu gestarteten NERVE-Engine gelesen. Der erfolgreiche Live-Durchlauf zeigte etwa 1,6 Sekunden Inferenzdauer; das ist ein Einzelwert, kein Leistungsbenchmark. Zuvor traten bei echten Aufrufen HTTP 503 bzw. ein Transport-Timeout auf. Es wurde kein anderes Modell und keine statische Ersatzantwort verwendet.

Die erweiterten Laufzeitprüfungen dieses Live-Durchlaufs meldeten freien Flug über 7,46 Block Wegstrecke, Go-to-Abstand zum Hoverziel 0,35 Block, Schwebehöhe 0,84 Block über dem Blockzentrum und erfolgreiche Rückkehr. Die größte gemessene Entfernung zur Spielerposition war 5,38 Block bei einem eingestellten Limit von 7. Getrennte deterministische Physiktests prüfen auch die Radiusgrenze selbst, bewegte Spieler, Ausweichbewegungen und vollständig blockierte Wiederherstellung. Die kontrollierten QA-Flugaktionen und UI-Prüftexte sind ausdrücklich Teststeuerung; sie werden nicht als spontane KI-Entscheidungen ausgegeben.

Die JUnit-Suite prüft Keyword-Grenzen, Chat-Trigger, Deduplizierung, Kontextbudgets einschließlich Inventarpriorisierung, expliziter Kürzungsmarkierungen, adversarial Escaping und vollständiger Ziel-IDs, gültige/ungültige Aktionen, Bewegung/Kollision/Teleport, Aufmerksamkeitswechsel, getrennte Blasen, Outbox-Neuladen/Kapazität, Secret-Trennung, sichere URLs, Retry/401-Verhalten, Timeout bei hängendem Body, Response-Größe, Abbruch, Retrievalfilter und Persistenz vor der Aktionsübergabe.

Der Clientstart funktioniert trotz nichtfataler Umgebungswarnungen zu fehlender Narrator-Bibliothek und nicht verfügbarem Auth-Service im Entwicklungsprofil. Die Tests melden ausdrücklich keinen erfolgreichen Online-Login oder Multiplayerbeitritt.

[Echte Gemma-Antwort im Client](images/qb2-google-live.png) · [Go-to](images/qb2-go-to.png) · [Gedankenblase](images/qb2-thought.png) · [Sprechblase und Sensorflächen](images/qb2-speech.png) · [Einstellungsmenü](images/qb2-settings.png) · [Craftoria Go-to](images/qb2-craftoria-go-to.png) · [Craftoria Gedankenblase](images/qb2-craftoria-thought.png) · [Craftoria Sprechblase](images/qb2-craftoria-speech.png)

## Inventare, Situationen und Katalog

Der erweiterte echte Clienttest hat das vollständige lokale Inventar, Rüstung, Nebenhand und eine vom integrierten Server befüllte Truhe durch die normale Menü-Synchronisierung geprüft. Nach dem Schließen war der Containerinhalt nicht mehr im aktuellen Sensorbild. Das verändert ausschließlich eine explizite Wegwerf-Testwelt. Ohne Opt-in-Testflag erzeugt QB2 keine Truhen oder Items.

Der erste Katalogdurchlauf ohne Craftoria schrieb 3.813 Registry-/Rezeptfakten in echtes NERVE; anschließend wurde die native Engine neu gestartet und alle Einträge erneut gelesen. 323 Rezepte wurden als teilweise auslesbar gekennzeichnet. Das sind Daten des Testclients und ausdrücklich kein vollständiger Craftoria-Katalog.

Ein zusätzlicher echter Google-Aufruf mit dem aktuellen Parser war erfolgreich; Laufzeit inklusive Wartezeit/Retry rund 49,6 Sekunden. Verfügbarkeit und Antwortzeit sind daher weiterhin eine externe Einschränkung. Modellgenerierte Situationsreflexionen werden im Integrations-Fixture unabhängig von öffentlicher Sprache vor der Aktionsübergabe journalisiert und anschließend real in NERVE persistiert. Ein eigener Netzwerktest prüft zusätzlich getrenntes Katalog-Retrieval und unverlierbare `partial`-Metadaten im Modellkontext.

Situationssensor-Tests prüfen Lebensverlust, Feuer und Untertauchen, keine erfundene Schadensursache sowie keine Wiederholung unveränderter Zustände. Modellkontexte kennzeichnen gekürzte Abschnitte; Inventarfragen erhalten mehr Budget.

Craftoria-Testaufbau: installierte Craftoria-1.37.0-Instanz mit 527 JARs, NeoForge 21.1.253. Mods werden in `run-craftoria/` referenziert, Konfiguration und KubeJS in diese isolierte Testinstanz kopiert; Originalspielstände werden nicht geöffnet. Der Entwicklungslauncher aktiviert im Gegensatz zum regulären Client GameTests und zusätzliche Jade-Prüfungen. Für diese QA sind GameTests deaktiviert und die fehlende UI-Übersetzung `config.jade.plugin_pipez.pipe` über ein reines Test-Resourcepack ergänzt. Keine Gameplay-Rezepte oder Mod-JARs werden dafür verändert. Der QA-Import verwendet einen kontrollierten Modellserver; reale Google-Aufrufe werden getrennt ausgewiesen.

Im vollständigen Craftoria-Durchlauf wurden 164.366 öffentliche Katalogfakten (32.542 Blöcke, 40.575 Items, 1.136 Entity-Typen, 90.113 Rezepte, davon 70.366 als teilweise auslesbar markiert) in die lokale NERVE-Datenbank unter `bridge/data/` persistiert und nach einem kalten Neustart vollständig gegen den Export verifiziert. Der abschließende Craftoria-Clienttest prüfte Inventare, Erholung aus neu platzierter Geometrie, freien Flug, Go-to (0,06 Block Abweichung), Rückkehr, Radiusgrenze und Blasen ohne Fehler.

## Nicht real geprüft

- Längerfristige Google-Verfügbarkeit, Kontingentgrenzen und systematische Antwortqualität. Ein erfolgreicher Live-Aufruf beweist keine dauerhafte Verfügbarkeit.
- Multiplayer auf einem echten entfernten Server ohne QB2; die Mod registriert keinerlei Serverentität oder Netzwerkpakete.
- Vollständige Portal-/Respawn-/Chunk-Unload-Sequenzen im laufenden Client. Die Lifecycle-Invalidierung ist implementiert; reine Teleportbewegung wird automatisiert getestet.
- Langzeittest, viele gleichzeitig auftretende Blockänderungen, weitere Modpacks/Shader und andere Betriebssysteme. Virtuelle AE2-/Netzwerklager sind keine vollständige Garantie des generischen Slot-Sensors.
- Native NERVE-I/O-Ausfälle wie volle oder defekte Datenträger; deren strukturierte Fehlerweitergabe bleibt ein Upstream-Befund.

## Manueller Testplan

1. JAR in einen regulären NeoForge-1.21.1-Client installieren. Ohne API und ohne NERVE starten: Kubus bewegt sich, K öffnet Einstellungen, direkte Ansprache liefert einen sicheren Statushinweis.
2. NERVE-Bridge und gültigen Google-Key konfigurieren. `@qb Was siehst du?` schreiben: lokale Antwort, lesbare Blase, UI bleibt bedienbar; kein Text wird an einen Server gesendet.
3. NERVE-Treffer im Overlay beobachten. Gedanken ohne Sprache erzeugen lassen; Gedankenblase und privater Memory-Eintrag, kein Gedanke im öffentlichen Chat.
4. Eine Maschine mehrfach betrachten. Ziel bleibt mehrere Sekunden bestehen, Antworten berücksichtigen Verlauf; gleiche Gedanken werden im Kurzzeitfenster nicht wiederholt. Andere Welt öffnen: keine Erinnerungsvermischung.
5. Bridge stoppen, Gedanken/Sprache erzeugen, Client beenden und neu starten. Bridge starten: Outbox wird nach Bestätigung geleert; Erinnerungen bleiben über Bridge-Neustarts erhalten.
6. API-Verbindung trennen oder 401/429 erzeugen. Keine Renderblockade, sichere Fehler, endliche Retries. Direkte Ansprache unterbricht Hintergrundreflexion.
7. Portal, Tod/Respawn, großer Teleport, Chunk-Grenzen und World-Unload prüfen. Keine veraltete Antwort bewegt den Kubus in der neuen Umgebung.
8. Auf Multiplayer-Server ohne QB2 beitreten. Lokale Äußerungen bleiben lokal. `serverChat` bewusst aktivieren, einmal prüfen und wieder deaktivieren; keine rekursive Eigenantwort.
9. Viele Blöcke verändern, schnell laufen/fliegen und Konfiguration mehrfach speichern. Queue-/Cachegrößen bleiben begrenzt; keine Threadzunahme über längere Zeit.
10. Eigenes Inventar, Rüstung, Nebenhand, Kiste und Maschineninventar öffnen: IDs, Mengen und Kontext stimmen. Kiste schließen: kein aktueller Blick hinein. Virtuelle Lager separat prüfen.
11. Katalogimport abwarten, mehrfach neu verbinden und Rezept-Reload durchführen: gleicher Katalog verwendet stabile IDs; neue Rezepte erhalten einen getrennten Scope; keine alten Varianten als aktuelle Anleitungen ausgeben.
12. Lebensverlust, Feuer und Untertauchen auslösen: nur beobachtete Zustände melden; optionale Reflexionen und situative Sprüche müssen mit passenden Keywords in NERVE landen.
13. Helle/dunkle Umgebung, unterschiedliche GUI-Skalen und kleine Fenster prüfen: Zeilenumbruch, Blasendauer, Transparenz und maskierte Secrets einschließlich Sprachausgabe.

## Handoff

QB2-Branch: `feat/qb2-companion`; Ausgangszustand: leerer Workspace ohne Git-Commit. Alle neuen Quellen liegen nach Verantwortlichkeit unter `src/main/java/dev/qb2`, dazu Tests, Bridge, Wrapper, Installer und Dokumentation.

NERVE: Branch `main`, Basis `e40f770`; geändert: `packages/nerve-bun/src/engine.ts`, neu: `test/wal_recovery_test.ts`. Die bereits vorhandene ungetrackte `.issues.json` wurde nicht verändert. Der überprüfbare Patch liegt unter `patches/nerve-wal-recovery.patch`.

Security-Auswirkung: Modellaktionen sind auf eine feste lokale Liste begrenzt; keine Block-/Command-/Dateioperationen. Secrets bleiben in separater lokaler Konfiguration. Serverchat ist opt-in. Gedächtnis wird authentifiziert und nach Welt/Spieler getrennt. Der Schutz eines Benutzer-API-Keys auf einem kompromittierten Desktop ist keine behauptete Garantie.

Merge-Empfehlung: QB2 und den fokussierten NERVE-Recovery-Fix übernehmen; nutzbar für einen betreuten Betatest. Frühere Google-Livetests waren erfolgreich, der abschließende Lauf scheiterte jedoch extern an HTTP 503 bzw. Transport-Timeout; vor einer breiten Produktfreigabe für unbeaufsichtigten Dauerbetrieb bleiben die schwankende externe API-Verfügbarkeit sowie die offenen Multiplayer-/Lifecycle-Prüfungen zu beachten. Rollback: JAR aus `mods/` entfernen, Bridge beenden; Gedächtnis und Outbox erhalten. Der NERVE-Fix ändert kein Dateiformat und kann als Codepatch zurückgenommen werden, würde dann aber den bestätigten Recovery-Fehler wieder ermöglichen.
