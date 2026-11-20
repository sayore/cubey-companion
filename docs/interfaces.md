# Schnittstellen

## Modell

`LanguageModel.complete(String context)` kapselt die externe Inferenz. Nur der Cognition-Worker ruft diese synchrone Schnittstelle auf. Google verwendet `POST {endpoint}/models/{model}:generateContent`, API-Key im Header `x-goog-api-key`. Gemma 4 erhält eine separate `systemInstruction`; Wahrnehmung, Chat und Erinnerungen stehen ausschließlich im User-Kontext. `thinkingLevel=minimal` hält interne Modellreflexion aus dem knappen Outputbudget heraus. JSON-Response-Mode wird nicht vorausgesetzt. OpenAI-kompatible Provider verwenden `POST {endpoint}/chat/completions` mit Bearer-Header und konfiguriertem Modell.

Maximal drei Versuche, exponentielles Backoff für 429/5xx und Transportausfälle. 401/403 werden nicht wiederholt. Jedes Timeout umfasst auch den Response-Body. Maximal 256 KiB Response. Redirects werden nicht verfolgt. Nur HTTPS oder Loopback-HTTP; keine Zugangsdaten/Queryparameter in URLs. Kontext- und Outputbudgets sind unabhängig. Ein Modell kann trotzdem ungültiges JSON liefern; dieses wird verworfen und diagnostiziert.

Antwortschema:

```json
{"action":"inspect","target":"minecraft:chest@1,64,2","thought":"Was wird hier aufbewahrt?","speech":null,"memory":null}
```

Aktionen: `observe`, `inspect`, `approach`, `go_to`, `explore`, `orbit`, `return`, `rest`, `recall`. Ziele für Untersuchung/Annäherung/Go-to/Orbit müssen zum Requestzeitpunkt sichtbar und innerhalb des Spielerbereichs erreichbar sein; der Kontext enthält dazu `reachableTargets`. Vor Ausführung wird erneut die Sichtbarkeit geprüft. Go-to und Annäherung verwenden einen Hoverpunkt über dem Block. `explore` steuert Beobachtungspunkte im Spielerbereich an. Gedanken maximal 360 Zeichen, Sprache 500. Optional `memory`: höchstens 600 Zeichen private Situationsreflexion, kein neuer ausführbarer Aktionsraum. Unbekannte Felder, Aktionen, Typen oder Steuerzeichen werden abgewiesen. `recall` plant eine Reflexion mit erneutem Retrieval, maximal innerhalb der allgemeinen Schedulergrenzen.

## QB2-NERVE-Bridge

Dies ist ein **neu implementiertes QB2-Protokoll**, nicht die native NERVE-API. Loopback-Port standardmäßig 17842; Bearer-Authentifizierung mit mindestens 24 Zeichen Token. Keine CORS-Freigaben, keine freie Dateipfad- oder Command-API.

- `POST /memories`: `Episode` mit `id`, `scope`, `kind`, `keywords`, `text`, `createdAt`; idempotenter Artikel-Upsert.
- `POST /knowledge`: Liste von 1–24 validierten `knowledge_*`-Episoden; idempotenter Batch-Upsert. Gesamter Request maximal 128 KiB; der Client gruppiert zusätzlich nach UTF-8-Größe. Kein neues NERVE-Schema oder zweiter Index.
- `POST /search`: `{scope, keywords, topK}` → Episodenliste. Maximal 1000 Keywords, Top-K 1–20.

Die Bridge validiert Eingaben und verwendet ausschließlich die öffentlichen Methoden `Engine.upsertArticle`, `Engine.getTopMatchesUnique`, `Engine.getArticleText` und `Engine.setMode` des vorhandenen `nerve-bun`-Package-Einstiegs. Native Suchscores mit einem kleinen Aktualitätsbonus bestimmen die Reihenfolge. Ein Scope-Präfix pro Keyword verhindert Trefferkonkurrenz zwischen Welten; der gespeicherte Episodentext enthält die originalen Registry-Keywords.

Die Bridge benötigt Bun und die zum lokalen OS passende gebaute NERVE-Bibliothek. Die hier geprüfte Native-Library ist Linux `libnerve_core.so`. Windows/macOS-Paketierung der Bridge wurde nicht geliefert. Die Mod selbst verwendet keine nativen NERVE-Bindings und kann eine extern betriebene HTTPS-Bridge nutzen.
