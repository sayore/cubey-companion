# Konfiguration

**K** öffnet das mehrseitige Menü. Speichern validiert alle Felder, bricht veraltete Requests ab und setzt den Kurzzeitzustand zurück. Langzeitgedächtnis und unbestätigte Outbox bleiben bestehen. Abbrechen verwirft Änderungen. „Test saved settings“ verwendet die zuletzt gespeicherte Konfiguration und benötigt eine geladene Welt.

| Feld | Standard / Bedeutung |
|---|---|
| provider | `google`; alternativ `openai` |
| endpoint | `https://generativelanguage.googleapis.com/v1beta` |
| model | `gemma-4-31b-it` |
| nerveEndpoint | `http://127.0.0.1:17842` |
| memoryTopK | 3, maximal 20 |
| perceptionRadius / exploreRadius | 10 / 7 Blöcke, maximal 24 / 20 |
| fieldOfView | 110 Grad |
| inferenceSeconds | 18, mindestens 5 Sekunden für Autonomie |
| tokenBudget / outputTokens | 2200 geschätzte Inputtokens / 350 Outputtokens |
| timeoutSeconds | 25 pro Versuch, maximal drei Versuche |
| autonomous | Neugier und gelegentliche relevante Gesprächsreaktion an |
| thoughts / speech / particles | Anzeige an |
| glow / bubbleOpacity | 0.85 / 0.8 |
| debug / paused / serverChat | aus |
| triggers | `@cube,@qb,qb2`; zusätzlich Satzende `QB` oder `cube` |

`config/qb2.json` enthält ausschließlich normale Einstellungen. `config/qb2-secrets.json` enthält `apiKey` und `nerveKey`, wird nicht in JAR oder Beispielkonfiguration gepackt und erhält POSIX-Modus 0600. Es handelt sich um eine lokale Desktop-Konfigurationsdatei, keine Verschlüsselung und keinen zentralen Secret Store. Unter Windows gelten geerbte Dateirechte. Schlüssel sind im Menü und im Screenreader maskiert; Logs und Diagnoseoverlay enthalten keine Keys oder fremden Response-Bodies.

Bridge-Umgebungsvariablen: `QB2_NERVE_REPO`, `QB2_MEMORY_DIR` (Standard `bridge/data`), `QB2_NERVE_PORT` (17842), `QB2_NERVE_TOKEN`. `QB2_NERVE_DEBUG=1` erlaubt lokale Import-/Recovery-Diagnosen ohne Tokenausgabe. Der Port bleibt an Loopback gebunden. Für externe Nutzung einen eigenen authentifizierten HTTPS-Zugang vor die Bridge setzen; keine offene Freigabe des Ports.

Der Token muss nach jedem Bridge-Neustart derselbe bleiben, solange der Client ihn gespeichert hat. Für einen neuen Token muss das Feld `nerveKey` im Client entsprechend aktualisiert werden. Die Bridge liest weder Google-Key noch Minecraft-Konfiguration.

[Google-Gemma-Dokumentation](https://ai.google.dev/gemma/docs/core), [Gemini-API-Preise und Kontingente](https://ai.google.dev/gemini-api/docs/pricing).

## Temporäre Projektzugänge für Live-Tests

Die lokale `.env` im Repository-Root nimmt `GOOGLE_API_KEY`, optional `QB2_MODEL` und `QB2_API_ENDPOINT` auf. Eine private Platzhalterdatei ist vorbereitet; die versionierbare Vorlage heißt `.env.example`. `.env` und weitere `.env.*` sind Git-ignoriert, ausgenommen die schlüssellose Vorlage. Der Liefer-ZIP verwendet explizite Quelldateien und enthält keine `.env`.

`python3 scripts/live-test.py` liest diese Datei ohne Shellausführung oder Variablenexpansion, reicht die Werte ausschließlich über die Prozessumgebung an den expliziten Gradle-Task `liveApiCheck` weiter und ruft den normalen Java-Provider auf. Der Task gehört nicht zu `build` oder `test`. `python3 scripts/client-smoke.py --live` übernimmt dieselbe `.env` für einen echten Clienttest mit Google und NERVE; Zugangsdaten werden nur in die private temporäre Testkonfiguration geschrieben und vorhandene Konfigurationen anschließend wiederhergestellt. Ohne Schlüssel wird vor dem Netzwerkaufruf abgebrochen. Kein API-Key erscheint als Kommandozeilenargument, Gradle-Property, HTTP-URL oder Testausgabe.
