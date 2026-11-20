# Architektur

QB2 besitzt Companion-Zustand, Aufmerksamkeit und sichere lokale Aktionsausführung. Minecraft besitzt Weltwahrheit. Google/Gemma erzeugt Absichten; NERVE besitzt das langfristige Gedächtnis. Die lokale Outbox ist ausschließlich ein Zustelljournal, kein zweiter Memory-Suchdienst. Keine KittyContracts-Integration.

```text
Clientereignis / begrenztes Sampling
 → unveränderlicher Snapshot
 → Aufmerksamkeitsziel + priorisierte Triggerqueue
 → NERVE-Retrieval (standardmäßig Top-3)
 → begrenzter Kontext → Google/OpenAI-Provider
 → JSON-Validierung + sichtbare Zielmenge
 → persistente Outbox für Gedanken/Sprache/ausgewählte Reflexionen
 → Rückgabe auf Clientthread → Bewegung/Blase/lokaler Chat
 → NERVE-Upsert und Bestätigung
```

## Verantwortlichkeiten

- `client/config`: getrennte lokale Einstellungen und Zugangsdaten, atomisches Speichern.
- `client/lifecycle`: NeoForge-Ereignisse, World-/Player-Lifecycle, Snapshot-Erstellung und Komposition.
- `companion/movement`: reine virtuelle Physik, Spielergrenze und persistente Flugziele; Collision-Prüfung wird vom Client injiziert. `FlightPlanner` plant Hoverpunkte über Blöcken und freie Beobachtungspunkte, `Motion` erzwingt die räumliche Grenze auch bei Trägheit und Ausweichbewegung.
- `companion/attention`: Hysterese, Mindestverweildauer und begrenzter Wiedererkennungscache.
- `perception/world`: maximal 40 Blockstrahlen und 32 nahe Entity-Kandidaten alle zehn Ticks; Registry-IDs und ausschließlich clientbekannte Daten.
- `perception/PlayerSituationSensor`: sichtbare Vitalwerte und Zustandswechsel ohne erfundene Schadensursachen.
- `perception/inventory`: lokales Spielerinventar und aktuell sichtbare synchronisierte Menü-Slots; keine geschlossenen Container.
- `perception/knowledge`: Registry-/Rezeptaufnahme auf dem Clientthread; `client/lifecycle/KnowledgeIndex` besitzt den Import-Lifecycle, den inhaltsabhängigen Katalog-Scope und die begrenzte Hintergrundzustellung. `memory/knowledge` enthält ausschließlich unveränderliche Fakten ohne Minecraft-Zugriff. Kein zweiter Suchdienst.
- `perception/chat`: 20 Nachrichten, Quellenkennzeichnung und adressierte Trigger.
- `cognition`: Kontextbudgetierung, genau ein Worker, Entscheidungen und persistente Ausgabe.
- `inference`: Java HttpClient, zwei Protokolle, Response-Limit und endliche Retries.
- `memory`: Episoden, Retry-Outbox und NERVE-Bridge-Zugriff.
- `rendering`: dunkle 0,25-Block-Geometrie mit vier weißblauen Sensorflächen, Blickrichtung, interpolierter Position/Rotation und sanften Lichtübergängen. `BubbleLayout` begrenzt Blasen auf 42 Prozent der projizierten Bildschirmbreite und 30 Prozent der Höhe; überlange Texte zeigen eine Auslassung.
- `ui`: kompaktes mehrseitiges Einstellungsmenü, maskierte Secret-Felder.
- `diagnostics`: ausdrücklich aktivierter Entwickler-Clienttest, Overlay im Controller.
- `bridge`: authentifizierter Loopback-Transport zum bestehenden NERVE-Package.

## Grenzen

Keine serverseitigen Entities, Pakete oder Modregistrierungen. Kein Zugriff auf unsichtbare Containerinhalte oder erfundene Block-Entity-Felder. Aktionen verändern keine Weltblöcke, Items oder Spielersteuerung. Netzwerkantworten dürfen nicht direkt Minecraft lesen oder verändern. Chat, Erinnerungen und Registry-/Anzeigenamen sind untrusted Daten im User-Kontext; sie können die Aktionsliste nicht erweitern.

Ein World-/Dimensions-/Playerwechsel oder großer Teleport invalidiert Requests und Kurzzeitzustand. Die Welt-/Spielerkennung wird gehasht; langfristige Erinnerungen bleiben innerhalb derselben Welt über Dimensionen hinweg abrufbar. Direkte Ansprache unterbricht Hintergrundreflexion. Warteschlangen sind begrenzt; Überlast verdrängt zuerst schwächere Trigger.

## Grenzen der Version 0.1

Hindernisvermeidung bremst und versucht nach oben sowie seitlich auszuweichen; es ist kein vollständiger 3D-Pfadplaner. Sichtproben approximieren den Sichtkegel. Das gesamte Spielerinventar und bis zu 256 Slots im geöffneten Menü werden erfasst. Virtuelle Netzwerklager und moddefinierte Spezialmenüs können unvollständig sein. Rezeptinformationen umfassen nur die generische clientverfügbare Schnittstelle; Flüssigkeiten, Energie und Spezialbedingungen sind gegebenenfalls nicht auslesbar. Änderungen werden für wiederholt sichtbare Proben erkannt; ein Klick wird als Versuch und nicht als bestätigter Abbau bezeichnet. Es gibt keine semantische Langzeit-Zusammenfassung: Wiederholungsschutz nutzt Zeitfenster, stabile IDs und begrenzte Verlaufsspeicher. Visuelle Neugier ist kein behaupteter emotionaler Zustand.
