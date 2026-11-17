# ADR-0008: Storage-Engine-Vergleich & Wahl von Engine C (Persistent Radix-32 Trie) für die Persistenz

- **Status:** Akzeptiert (Stufe 7)
- **Datum:** 2026-11-17

## Kontext

In Stufe 7 (`P-02`–`P-04`, `H3`–`H4`, Workloads `W1`–`W7`) muss VEG empirisch klären, welche interne Speicherrepräsentation die semantische Stärke des Graphmodells (Entity-Attribute, Reifikation, Commits, 500+ Branches, `asOf`-Zeitreisen, ACLs) ohne unverhältnismäßige Speicher- oder Latenzkosten trägt.

## Untersuchte Alternativen

Alle Engines teilen sich über [CommitPublicationTarget](file:///C:/Users/thomas/Projekte/veg/veg-engine-memory/src/main/java/io/veg/engine/memory/CommitPublicationTarget.java), `PermissionEvaluator`, `SemanticInferenceEngine` und `GraphQuery` dieselben Vertragstests (`Stage1`–`Stage6`, 350 Tests in `veg-engine-memory`):

1. **Engine A (`InMemoryVersionedDatabase`)**: Mutable `HashMap`/`ArrayList` pro Commit kopiert (`Baseline`).
2. **Engine B1 (`CompactVersionedDatabase(1)`)**: Sortierte primitive `long[]`/CSR-Arrays pro Commit + legales `Value`-Payload-Interning (`ValueInterner`) unter Wahrung getrennter `EntityId`s.
3. **Engine B2 (`CompactVersionedDatabase(10)`)**: Journal-as-Truth (`Commit.changes()`) mit periodischen CSR-Checkpoints alle 10 Commits.
4. **Engine C (`PersistentTreeVersionedDatabase`)**: Strukturell geteilte 32-Wege-Bit-Partitionierte Radix-Tries (`PersistentLongMap`) — pro Commit werden nur die tatsächlich geänderten `O(log32 N)` Pfade kopiert.

## Gemessenes Ergebnis (`entities=2.000`, `commits=50`, `branches=500`, `seed=42`, Java 25)

- **Speicher nach Basisstand + Commits:** Engine A `21,34 MB` (`7.203 B/ent`) → B1 `9,19 MB` (`3.101 B/ent`) → B2 `1,42 MB` (`480 B/ent`) → **Engine C `1,13 MB` (`380 B/ent`, -94,7 %)**.
- **500 Branches (`W4`, davon 50 mit lokalem Edit):** Engine A `20,51 MB` → B1 `8,49 MB` → B2 `0,74 MB` → **Engine C `0,07 MB` (`69.952 B`, 293× kleiner als Engine A)**.
- **Write-Durchsatz (`W2`) & Historienzugriff (`W7 asOf p50`):**
  - **Engine C** erreicht `1.593,7 Commits/s` und **`300 ns`** auf `asOf` (selbst unter 40 % historischen Reads `W1b`: `500 ns` p50).
  - **Engine B2** fällt bei kaltem `asOf` durch Delta-Replay auf **`845.500 ns` (~0,85 ms)** ab (~2.800× langsamer als Engine C).

## Entscheidung

- **Primärarchitektur für Stufe 8 (Persistenz & Recovery):** **Engine C (`PersistentTreeVersionedDatabase`)** kombiniert mit einem append-only Write-Ahead-Log (WAL) + periodischen Wurzel-Checkpoints. Sie liefert sowohl minimalen Speicherzuwachs pro Commit/Branch als auch deterministische Sub-Mikrosekunden-Lesezeiten auf beliebigen historischen Commits ohne Journal-Rekonstruktionsstrafe.
- **Ergänzung:** `CompactGraphSnapshot` (Engine B) bleibt als kompaktes Read-Only-/Archivsegment-Format für spätere Kaltarchivierung (`P-07`) erhalten.
- **Revisionskriterium:** Sollten Off-Heap-/Disk-B-Tree-Messungen in Stufe 8 zeigen, dass der GC-/Objekt-Overhead einzelner Radix-Trie-Knoten bei >1M Entities zu hoch bleibt, werden flache Paged-/Block-Radix-Knoten auf binären Byte-Pages (Off-Heap/Disk) statt einzelner Java-Records genutzt — bei identischem Persistent-Trie-Algorithmus.
