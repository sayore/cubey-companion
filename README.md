# QB2 0.1

Clientseitiger Begleiter für **Minecraft Java 1.21.1 / NeoForge 21.1.253+ (21.1.x) / Java 21**. Ein 0,25 Block großer, dunkler Kubus mit vier weißblauen Sensorflächen beobachtet aus seiner eigenen Position, verfolgt Aufmerksamkeitsziele und setzt validierte Modellabsichten um. Standard: **Google Gemini Developer API, `gemma-4-31b-it`**, kein lokales Modellhosting.

## Bauen und installieren

```bash
./gradlew clean build
./scripts/install.sh /pfad/zur/minecraft-instanz
```

JAR: `build/libs/qb2-0.1.0.jar`. Der Installer verweigert die Installation bei einer vorhandenen QB2-JAR. Keine Serverinstallation nötig. Keine anderen Spieler sehen lokale QB2-Nachrichten. Ein vorhandener Minecraft-Client wird nicht automatisch verändert.

## Einrichten

1. NERVE-Core bauen: `cargo build --release --manifest-path /mnt/lfs/lfs/repos/nerve/packages/nerve-core/Cargo.toml`.
2. Lokale Bridge starten (Bun benötigt):

   ```bash
   export QB2_NERVE_REPO=/mnt/lfs/lfs/repos/nerve
   export QB2_NERVE_TOKEN="$(openssl rand -hex 32)"
   bun bridge/server.ts
   # Oder komfortabel mit persistentem Token (~/.config/qb2/nerve-token):
   ./scripts/start-bridge.sh
   # Optional als systemd-User-Service: scripts/qb2-bridge.service
   ```

3. Im Spiel **K** drücken oder Mods → QB2 → Config öffnen. API-Key und denselben NERVE-Token in die maskierten Felder `apiKey` und `nerveKey` eintragen. Optional können ein **Fallback-Modell** (`fallbackProvider`, `fallbackEndpoint`, `fallbackModel`, `fallbackApiKey`), **Audio-Cues** (`sounds`) und der **Ziel-Lichtstrahl** (`showTargetBeam`) konfiguriert werden. Über **Inspektor** lassen sich Erinnerungen der aktuellen Welt in NERVE durchsuchen und einzeln löschen. Der Token wird nicht von der Bridge ausgegeben. Die Maskierung gilt auch für Sprachausgabe.
4. `provider=google`, `endpoint=https://generativelanguage.googleapis.com/v1beta`, `model=gemma-4-31b-it` verwenden. Speichern. Mit `@qb Hallo` im Chat ansprechen. Diese Nachricht bleibt lokal.
5. Optional `debug` aktivieren; das Overlay zeigt API- und NERVE-Zustand. **Test saved settings** prüft die gespeicherte Konfiguration in einer geladenen Welt.

Ein Google-AI-Studio/API-Key mit Zugriff auf das Modell ist erforderlich. Google-One-Abonnement, API-Projekt und Kontingente müssen im eigenen Google-Konto geprüft werden. Keine Zugangsdaten in Repository, Chat oder Logs einfügen.

NERVE wird wirklich als Datenbanktreiber verwendet; die Bridge importiert den vorhandenen Package-Einstieg von `nerve-bun`. Es gibt keinen nachgebauten Suchindex. Die Bridge ist ein neuer QB2-Transportadapter, keine behauptete bestehende NERVE-HTTP-API. Der lokale NERVE-WAL-Recovery-Fix unter `patches/` ist für zuverlässigen Wiederanlauf erforderlich und im angegebenen NERVE-Checkout bereits angewendet.

## Lokaler Google-Livetest ohne Ingame-Eingabe

Im Projekt liegt eine vorbereitete, **Git-ignorierte `.env`**. Dort `GOOGLE_API_KEY=` ausfüllen; Modell und API-Endpunkt sind bereits gesetzt. `.env.example` enthält die Vorlage ohne Schlüssel.

```bash
python3 scripts/live-test.py
python3 scripts/client-smoke.py --live
```

`live-test.py` verwendet den tatsächlichen Java-Provider der Mod und prüft einen echten Google-Aufruf einschließlich QB2-Antwortvalidierung. Er zeigt nur Erfolg/Laufzeit oder einen sicheren Fehlerstatus. Schlüssel und Antworttext werden nicht ausgegeben. Die Datei wird als einfache Konfiguration gelesen, nicht als Shellcode ausgeführt. Keine automatische Ausführung im normalen Build, keine Änderung der Ingame-Konfiguration und keine Aufnahme der `.env` ins Lieferpaket. `client-smoke.py --live` öffnet zusätzlich Minecraft, nutzt deinen Schlüssel vorübergehend für echte Gemma-Inferenz und prüft Flug, Blasen und NERVE. Vorhandene Einstellungen werden anschließend wiederhergestellt; ohne `--live` nutzt dieser Clienttest einen ausdrücklich kontrollierten Modell-Fixture. Nach dem Test kann die `.env` geleert oder gelöscht werden.

## Bedienung

QB2 bewegt sich auch ohne API-Verbindung, untersucht sichtbare Objekte, umfliegt Hindernisse per begrenzter 3D-Voxel-Wegfindung (`VoxelPathfinder`) und kehrt zum Spielerbereich zurück. `go_to` lässt ihn über einer sichtbaren Blockoberfläche schweben; `explore` steuert freie Beobachtungspunkte an. Bei aktiven Zielen markiert optional ein dezenter Lichtstrahl samt Zielrahmen den fokussierten Block (`showTargetBeam`). Neben **K** (Einstellungen) stehen in den Minecraft-Steuerungsoptionen unter **QB2 Begleiter** weitere belegbare Hotkeys bereit (**Pause/Fortsetzen**, **Herrufen**, **Schau mal hier**). Geöffnete Kisten/Container merkt sich QB2 samt Koordinaten und Inhalt in NERVE (`container_memory`), und längere Sitzungen werden automatisch zu `session_summary`-Einträgen verdichtet. Bei temporären API-Fehlern (429/5xx/Timeout) greift ein konfigurierbares Fallback-Modell sowie exponentieller Backoff (sichtbar im Overlay und als bernsteinfarbener Sensor-Puls). Bewegung, Orientierung, dezente Sound-Cues (`sounds`) und sanfte Lichtpulse prägen seine Erscheinung; es gibt keine menschlichen Gesichtsanimationen. Gedanken und Sprache sind getrennt; Sprache erscheint lokal im Chat. `serverChat` versendet nach bewusster Aktivierung QB2-Sprache als normalen Spielerchat und ist standardmäßig aus. Pause stoppt Verhalten, Anzeige und neue Requests; Einstellungen werden beim Speichern übernommen.

Inferenz und episodische Zustellung laufen auf einem Hintergrundworker; ein separater begrenzter Worker importiert Spielwissen. Gedanken und Sprache werden vor der Anzeige lokal journalisiert und anschließend an NERVE geliefert. Bei Ausfall bleibt eine begrenzte persistente Queue erhalten. Bei voller Queue stoppt die Kognition; unbestätigte Erinnerungen werden nicht verdrängt.

## Inventare und Packwissen

QB2 sieht das gesamte lokale Spielerinventar einschließlich Rüstung/Nebenhand und die synchronisierten Slots eines **gerade geöffneten** Containers. Geschlossene Kisten werden nicht ausgelesen. Bis zu 256 Nicht-Spieler-Slots werden erfasst; größere/virtuelle Mod-Menüs können nur teilweise verfügbar sein. Der Modellkontext ist budgetiert und kennzeichnet gekürzte Abschnitte.

Nach dem Rezept-Sync indexiert QB2 automatisch echte Block-, Item- und Entity-Registries sowie synchronisierte Rezepte in NERVE. Das gilt auch für die tatsächlich geladene Craftoria-Version mit ihren KubeJS-/Datapack-Anpassungen. Das Overlay zeigt Fortschritt und teilweise auslesbare Rezepte. Generische Rezeptdaten ersetzen bei komplexen Maschinen keinen EMI/JEI-Rezeptviewer. Es werden weder Mod-Lore noch unbekannte Zutaten erfunden.

Gedanken und eigene Sprüche werden bereits immer gespeichert, mit Keywords der jeweiligen Situation. Das optionale Modellfeld `memory` ergänzt ausdrücklich ausgewählte Erinnerungen; sie sind als Modellreflexion gekennzeichnet. Humor wird nicht durch feste Spruchlisten erzwungen.

Ein isolierter Packtest ist möglich, ohne die vorhandene Instanz zu ändern:

```bash
python3 scripts/client-smoke.py --craftoria /pfad/zur/instanz/minecraft --catalog-output build/craftoria-catalog.json
# Optional einen bereits exportierten öffentlichen Katalog in eine laufende Bridge importieren:
python3 scripts/import-catalog.py build/craftoria-catalog.json
```

Der Import verwendet `QB2_NERVE_TOKEN` der bestehenden Bridge. Der normale Client importiert selbstständig; ein manueller Import ist nicht erforderlich.

## Prüfen

```bash
./gradlew test
python3 scripts/nerve-smoke.py
python3 scripts/client-smoke.py
```

Der letzte Befehl öffnet einen echten Minecraft-Client, erstellt eine Wegwerfwelt unter `run-smoke/`, verwendet echtes NERVE und einen **kontrollierten HTTP-Modell-Testserver** und beendet sich anschließend. Er ist kein Google-/Gemma-Livetest. Screenshots und Konsole liegen unter `run-smoke/`. `./gradlew runClient` startet einen normalen Entwicklungsclient.

Details: [Architektur](docs/architecture.md), [Schnittstellen](docs/interfaces.md), [Gedächtnis](docs/memory.md), [Konfiguration](docs/configuration.md), [Testbericht und manueller Testplan](docs/testing.md).
