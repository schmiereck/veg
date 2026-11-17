# Auswertung: Stage 2 Assertion-Modell-Vergleich (`Option A` vs. `Option B`)

## 1. Testumgebung & Aufbau

- **Datum / Zeitstempel:** `2026-11-17T11:29:58Z`
- **Engine:** `InMemoryGraphEngine` (Engine A) auf OpenJDK 25.0.3 (`Windows 11 amd64`, 12 CPUs)
- **Workload:** `pairCount = 5.000`, `seed = 42`, Dichten `k ∈ {1, 2, 5}` Aussagen pro Paar (mit je `weight`, `confidence`, `supportedBy -> SourceDocument`), `warmup = 1`, `iterations = 3`, `querySamples = 1.000`
- **Semantische Äquivalenz:** In beiden Modellen liefert dieselbe Abfrage (`worksOn` mit `confidence >= 0.80` und `weight >= 0.50`) exakt identische Trefferzahlen (`142` bei `k=1`, `130` bei `k=2`, `406` bei `k=5`).

---

## 2. Gemessene Ergebnisse (Iteration 3 — warm)

| Dichte `k` | Modell | Entities / Aussage | Edges / Aussage | Write (ms) | Write (assert/s) | Query p50 / p99 (ns) | ~Heap / Aussage |
|---|---|---|---|---|---|---|---|
| **k = 1** *(5.000 Aussagen)* | Option A (`Connection + Assertions`) | 14,17 | 9,04 | 31,476 | 158.850,68 | 2.000 / 7.400 | 3.304,31 B |
| **k = 1** *(5.000 Aussagen)* | Option B (`Edge = Assertion`) | **9,16** | **6,03** | **23,230** | **215.238,92** | **900 / 2.700** | **2.221,70 B** |
| **k = 2** *(10.000 Aussagen)* | Option A (`Connection + Assertions`) | 12,08 | 8,02 | 78,569 | 127.276,01 | 1.900 / 3.800 | 2.961,26 B |
| **k = 2** *(10.000 Aussagen)* | Option B (`Edge = Assertion`) | **8,58** | **6,02** | **27,989** | **357.283,22** | **1.300 / 3.400** | **2.109,80 B** |
| **k = 5** *(25.000 Aussagen)* | Option A (`Connection + Assertions`) | 10,83 | 7,41 | 180,613 | 138.417,27 | 3.200 / 9.400 | 2.670,76 B |
| **k = 5** *(25.000 Aussagen)* | Option B (`Edge = Assertion`) | **8,23** | **6,01** | **143,368** | **174.375,94** | **1.900 / 4.000** | **2.167,96 B** |

---

## 3. Zentrale Erkenntnisse

1. **Struktur- und Speicherersparnis von Option B (`Edge = Assertion`):**  
   Option B spart konsequent die `Connection`- und `Assertion`-Zwischenknoten samt Indirektionskanten ein:
   - Bei `k = 1` (der häufigste Fall in Fachmodellen) benötigt Option B **~35 % weniger Entities** (`45.802` vs. `70.846`), **~33 % weniger Kanten** (`30.153` vs. `45.180`) und **~33 % weniger Heap pro Aussage** (`~2.222 B` vs. `~3.304 B`).
   - Selbst bei `k = 5` Aussagen auf demselben Paar amortisiert sich die `Connection`-Hülle von Option A noch nicht: Option B benötigt weiterhin **~24 % weniger Entities** (`205.802` vs. `270.846`) und **~19 % weniger Heap pro Aussage** (`~2.168 B` vs. `~2.671 B`).
2. **Schnellere Traversals & Schreiboperationen:**  
   Da Option B in Abfragen direkt die indizierte Prädikat-Liste `findOutgoingEdges(person, worksOn)` auswertet, statt zuerst über alle `Connection`-Objekte und `PredicateTag`-Kanten zu filtern, sinkt die p50-Latenz um **~32–55 %** (`900–1.900 ns` vs. `1.900–3.200 ns`).
3. **Konsequenz für das Kerndesign ([ADR-0001](../../adr/0001-semantic-assertions-on-relations.md)):**  
   Option B bleibt das verbindliche Kernmodell von VEG. Option A bleibt problemlos als *fachliches Schema-Muster* (z. B. ein Objekttyp `ProjectAssignment` mit Unteraussagen) abbildbar, wenn ein Anwendungsfall explizit eine gemeinsame Hülse mit geteilten Metadaten erfordert.
4. **Weiterer Optimierungshebel für Stufe 7:**  
   Selbst in Option B entfallen bei voll annotierten Aussagen noch ~2,1–2,2 KB Heap pro Aussage, weil `Weight`- und `Confidence`-Dezimalwerte als eigenständige `EntityNode`s + `HAS_MUTABILITY`-Kanten + `HashMap`-Einträge abgelegt werden. Stufe 7 (Engine B/C) wird messen, wie weit kompakte Wert- und Kantenrepräsentationen diesen Wert bei gleicher logischer Identität drücken können.
