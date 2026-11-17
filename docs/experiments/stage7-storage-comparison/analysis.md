# Auswertung: Stage 7 Storage-Engine-Vergleich (`Engine A` vs. `B1` vs. `B2` vs. `C`)

## 1. Testumgebung & Aufbau

- **Zeitstempel (UTC):** `2026-11-17T14:27:27.123665500Z`
- **JVM / Hardware:** OpenJDK `25.0.3` (`OpenJDK 64-Bit Server VM`), `Windows 11` (`amd64`), `12` CPUs, `-Xmx` (`max_heap_bytes`) `3.162.505.216` (~`3,0 GB`)
- **Parameter:** `entityCount = 2.000` (resultierend `3.107` Entities, `1.922` Edges im Basisstand inkl. 5-Hop-Ketten, ACL-Regeln & Prädikathierarchie), `commitCount = 50` (zzgl. Schreibanteil aus `W1a`/`W1b` -> `178` Commits je Engine), `branchCount = 500` (`501` Branches gesamt), `seed = 42`, `warmup = 1`, `sampleQueries = 400`
- **Semantische Äquivalenz (`P-01`):** Alle 4 Backends bestehen sämtliche Contract-Suites (`Stage1`–`Stage6`, **350 Tests** in `veg-engine-memory`) mit `0` Abweichungen.

---

## 2. Gemessene Ergebnisse (`results.csv`)

### A. Speicherverbrauch, Write-Durchsatz (`W2`), Merge (`W5`) & Branch-Skalierung (`W4`)

| Engine | Heap Base+50 Commits | ~Bytes / Base-Entity | `W2` Commits/s | `W2` Commit p50 / p99 (ns) | `W4` 500 Branches (ms) | `W4` Branch-Heap-Delta | `W5` Merge (ms) |
|---|---|---|---|---|---|---|---|
| `ENGINE_A_HASHMAP` | `22.378.880 B` (21,34 MB) | `7.202,73 B` | `1.454,27` | `529.000 / 3.360.500` | **`16,462`** | `20.507.720 B` | `11,017` |
| `ENGINE_B1_COMPACT_CSR` | `9.634.560 B` (9,19 MB) | `3.100,92 B` | `604,82` | `1.417.600 / 5.200.900` | `45,657` | `8.488.024 B` | `10,507` |
| `ENGINE_B2_JOURNAL_CKPT10` | `1.490.768 B` (1,42 MB) | `479,81 B` | `1.021,86` | `832.200 / 2.959.200` | `55,399` | `737.712 B` | **`7,392`** |
| **`ENGINE_C_PERSISTENT_TREE`** | **`1.180.512 B` (1,13 MB)** | **`379,95 B`** | **`1.593,68`** | **`501.400 / 3.870.600`** | `29,179` | **`69.952 B`** | `9,837` |

### B. Lese-/Schreib-Mix (`W1`), Traversals (`W3`), Commit-Granularität (`W6`) & Historie (`W7`)

| Engine | `W1a` (90/9/1) p50 / p99 (ns) | `W1b` (50/10/40) p50 / p99 (ns) | `W3` 1-Hop p50 (ns) | `W3` 3-Hop p50 (ns) | `W3` 5-Hop+ACL p50 (ns) | `W6` Small vs. Large (ms) | `W7` Cold `asOf` p50 / p99 (ns) | `W7` Full Replay (ms) |
|---|---|---|---|---|---|---|---|---|
| `ENGINE_A_HASHMAP` | `600 / 418.200` | `1.000 / 471.800` | `600` | `500` | **`3.511.100`** | `2,868` vs. `0,316` | `800 / 2.400` | `1,083` |
| `ENGINE_B1_COMPACT_CSR` | **`400`** `/ 1.714.500` | `1.200 / 1.150.600` | `400` | `500` | `4.889.000` | `10,902` vs. `0,674` | `800 / 1.800` | `1,410` |
| `ENGINE_B2_JOURNAL_CKPT10` | **`400`** `/ 2.273.200` | `850.300 / 2.870.800` | `400` | `500` | `4.727.200` | `14,461` vs. `0,655` | `845.500 / 1.058.500` | **`1,044`** |
| **`ENGINE_C_PERSISTENT_TREE`** | `500 / 1.386.600` | **`500 / 516.800`** | **`300`** | `500` | `3.564.400` | `7,731` vs. `2,134` | **`300 / 1.200`** | `3,783` |

---

## 3. Bewertung der Hypothesen (Abschnitt 8 des R&D-Konzepts)

1. **Hypothese 1 & 2 (Kompakte Repräsentation + legales Value-Interning vs. naive Maps):** **Bestätigt.**
   Selbst mit einem vollständigen Snapshot pro Commit senkt `ENGINE_B1_COMPACT_CSR` den Retained Heap um **56,9 %** (`9,19 MB` vs. `21,34 MB`), weil sortierte `long[]`/CSR-Arrays keine `HashMap.Node`-/`ArrayList`-Wrapper je Entity erzeugen und `ValueInterner` doppelte immutable Literale legal zusammenfasst (bei voller Wahrung getrennter `EntityId`s, `I-07`). Der Nachteil von B1 ist der volle Sortier-/Aufbauaufwand je Commit (`W2`: `604,8 c/s` vs. `1.454,3 c/s`).
2. **Hypothese 3 (Strukturelle Teilung über Radix-32-Bäume in Engine C):** **Eindrucksvoll bestätigt.**
   `ENGINE_C_PERSISTENT_TREE` reduziert den Speicherbedarf über `178` Commits auf **1,13 MB** (**-94,7 % vs. Engine A**) und den Speicheraufwand von `500` parallelen Branches (`W4`) auf **`69.952 B`** (**293× kleiner als Engine A** mit `20,51 MB`). Gleichzeitig erreicht Engine C den höchsten `W2`-Commit-Durchsatz (**`1.593,7 c/s`**) und die schnellsten Punkt-/`asOf`-Reads (**`300 ns`**).
3. **Hypothese 4 (Journal-as-Truth + Checkpoints `B2` vs. operative/historische Last):** **Bestätigt mit klarer Warnung.**
   `ENGINE_B2_JOURNAL_CKPT10` drückt den Speicherverbrauch auf `1,42 MB` (`479,8 B/ent`) und beschleunigt `W1a` (90/9/1, p50 `400 ns`), bricht aber im historischen Workload `W1b` (40 % `asOf`) und im kalten `W7 asOf`-Read (`845.500 ns` = `0,85 ms`, Faktor **~2.800× langsamer als Engine C**) ein, sobald uncheckpointete Commits per Delta nachgefahren werden müssen.
4. **Hypothese 5 (Graphweite Rechtepropagation & Traversalkosten):** **Bestätigt.**
   Die uncached 5-Hop-Propagation + `explainAccess` + Inferenz (`W3 5-Hop+ACL`) benötigt unabhängig von Engine A/B/C **`3,5–4,9 ms`**. Spezifische Materialisierungen/Caches für ACL-Erreichbarkeit bleiben für Stufe 10 erforderlich.
5. **`W6` Commit-Granularität:** Wenige größere Transaktionen übertreffen viele Minicommits über alle Engines deutlich (`0,32–2,13 ms` vs. `2,87–14,46 ms`).

---

## 4. Explizite Go-/No-Go-Entscheidung für Stufe 8

- **GO mit `ENGINE_C` (`PersistentTreeVersionedDatabase`) + Append-Only WAL / Checkpoint-Log als primäres Speicher- und Persistenzmodell in Stufe 8:**  
  Engine C vereint als einzige Architektur niedrigen Speicherverbrauch über hunderte Commits und Branches (`-94,7 %` Heap vs. A, `-99,7 %` Branch-Overhead), hohen Durchsatz bei kleinen Audit-Commits (`1.593,7 c/s`) **und** konstante Sub-Mikrosekunden-Latenz bei historischen `asOf`-Zugriffen (`300 ns`) ohne Journal-Rekonstruktionsstrafe.
- **Sekundäre Rolle für `CompactGraphSnapshot` (Engine B):** Die CSR-Array-Repräsentation eignet sich als optionales komprimiertes Kaltarchiv-/Segmentformat (`P-07`) für ältere, selten gelesene historische Snapshots.
- **No-Go** für die Fortführung von Vollkopie-`HashMap`-Snapshots (`Engine A`) über `100k+` Entities/Commits hinweg.
