# Auswertung: Stage 0/1 Baseline (Engine A — `InMemoryGraphEngine`)

## 1. Testumgebung

- **Datum / Zeitstempel:** `2026-11-17T11:06:22Z`
- **Engine:** `InMemoryGraphEngine` (Engine A — Referenz-Implementierung mit `HashMap` / `ArrayList` / unveränderlichen `record`s)
- **JVM:** OpenJDK 25.0.3 (`OpenJDK 64-Bit Server VM`)
- **OS / Architektur:** Windows 11 (`amd64`), 12 logische CPUs, Max Heap: `3.162.505.216` Bytes (~3,0 GiB)
- **Workload:** `personCount = 10.000`, `seed = 42`, `sharedValueRatio = 0.50`, `provenanceRatio = 0.25`, `warmup = 2`, `iterations = 5`, `readSampleQueries = 2.000`

---

## 2. Gemessene Ergebnisse

Pro Durchlauf entstehen `69.203` Graph-Entitäten (Bootstrap-Kern + `69.091` Domänenoperationen inkl. Schema, `10.000` Personen, `500` Quelldokumente, Attribut-Entitäten und `35.559` Kanten inklusive Provenienz-/Confidence-Meta-Kanten):

| Iteration | Write (ms) | Write (ops/s) | 1-Hop p50 / p95 / p99 (ns) | 2-Hop p50 / p95 / p99 (ns) | Heap-Delta (Bytes) | ~Bytes / Entity | ~Bytes / Edge |
|---|---|---|---|---|---|---|---|
| 1 | 41,251 | 1.674.880,55 | 600 / 1.200 / 2.700 | 600 / 1.200 / 1.800 | 15.279.416 | 220,79 | 429,69 |
| 2 | 32,308 | 2.138.490,73 | 700 / 1.200 / 1.500 | 600 / 1.200 / 1.500 | 15.409.112 | 222,67 | 433,34 |
| 3 | 31,069 | 2.223.785,05 | 600 / 1.100 / 1.400 | 600 / 1.200 / 1.500 | 15.366.216 | 222,05 | 432,13 |
| 4 | 31,287 | 2.208.276,20 | 800 / 1.500 / 1.900 | 600 / 1.300 / 1.500 | 15.366.248 | 222,05 | 432,13 |
| 5 | 30,353 | 2.276.279,46 | 600 / 1.100 / 1.400 | 600 / 1.200 / 2.500 | 15.366.256 | 222,05 | 432,13 |

---

## 3. Interpretation & Einschränkungen

1. **Speicherbedarf pro Entity (~222 Bytes/Entity):**  
   Die Messungen stützen Hypothese 1 aus Abschnitt 8 des Konzepts: Eine naive Java-Objekt- und `HashMap`-Repräsentation benötigt im Schnitt **~222 Bytes pro logischer Entity** (~14,65 MB für `69.203` Entities). Ursachen sind `HashMap.Node`-Allokationen in vier Maps (`entities`, `edges`, `outgoingEdges`, `incomingEdges`), geboxte `EntityId`-Records, `Optional`-Wrapper und individuelle `ArrayList`-Adjazenzlisten.
   - *Hochrechnung (zu validieren in Stufe 7):* Bei 1.000.000 fachlichen Objekten (~7 Mio. Entities bei gleicher Attributdichte) würde Engine A grob ~1,5 GB reinen Live-Heap benötigen, bevor überhaupt Historie oder Branches dazukommen.
2. **Schreibdurchsatz & Lese-Latenzen:**  
   Im warmgelaufenen Zustand erreicht Engine A **~2,14–2,28 Mio. Operationen/s** beim Graphaufbau und **600–800 ns (p50)** bzw. **1.400–2.700 ns (p99)** für 1-Hop- und 2-Hop-Traversals (inkl. strenger Typ-, Domain- und Range-Prüfung beim Schreiben).
3. **Methodische Einschränkungen dieser Baseline:**
   - Die Speichermessung (`settledUsedHeapBytes`) basiert auf `Runtime.totalMemory() - freeMemory()` nach zweimaligem `System.gc()` — dies ist eine grobe Heap-Delta-Schätzung für Stufe 0 und ersetzt keine präzise JOL-/Objektgraph-Vermessung oder JMH-Mikrobenchmarks (geplant für Stufe 7).
   - Alle Messungen liefen rein Single-Threaded ohne Transaktions-Deltas, Historien-Protokoll oder Disk-Persistenz.
