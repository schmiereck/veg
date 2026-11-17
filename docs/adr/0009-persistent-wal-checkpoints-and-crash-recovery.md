# ADR-0009: Persistentes WAL, komprimierte Checkpoints, Kaltarchivierung & Crash-Recovery

- **Status:** Akzeptiert (Stufe 8)
- **Datum:** 2026-11-17

## Kontext

In Stufe 7 ([ADR-0008](0008-storage-engine-comparison-and-persistent-tree-selection.md)) wurde **Engine C** (`PersistentTreeVersionedDatabase` auf 32-Wege-Radix-Tries) als Sieger ausgewählt. Stufe 8 (`P-06`, `P-07`, `P-08`, `T17`) erfordert nun dauerhafte Disk-Persistenz, schnelle Neustart-Rekonstruktion, verlustfreie Kompaktierung/Kaltarchivierung historischer Zustände, Backup/Restore sowie Crash-Festigkeit bei unvollständigen Schreibvorgängen.

## Architektur-Entscheidung (`veg-engine-persistent`)

`PersistentDiskVersionedDatabase` kombiniert Engine C im Hauptspeicher mit einer viergeteilten Disk-Struktur im Datenbankverzeichnis (`dataDir`):

1. **Append-Only Write-Ahead Log (`wal.log`, `WriteAheadLogStore`)**  
   - Jeder veröffentlichte Commit wird binär (`BinaryGraphCodec`) in einem selbstprüfenden Frame geschrieben:  
     `[magic=0x56454757 : 4B][version=1 : 2B][payloadLen : 4B][crc32c : 4B][payload : N B][trailer=0x454E4443 : 4B]`  
   - Konfigurierbarer `DurabilityMode`: `FSYNC_EVERY_COMMIT` (`FileChannel.force(true)`) oder `FLUSH_OS_BUFFER`.

2. **Atomare Wurzel-/Branch-/ACL-Metadaten (`refs.meta`)**  
   - Speichert aktiven Branch, Branch-Heads, Tags, Branch-Leserestriktionen (`S-05`), letztes Checkpoint, Kaltarchiv-Grenze sowie den nächsten `EntityId`-Zählerstand.  
   - Schreibvorgang erfolgt ausschließlich über `.tmp` + `force(true)` + atomares Ersetzen (`Files.move(..., REPLACE_EXISTING)`), geschützt durch `MAGIC_REFS` (`0x56454752`) + `CRC32C`.

3. **Periodische & manuelle Checkpoints (`checkpoints/ckpt-<commitId>.bin.gz`)**  
   - GZIP-komprimierter Materialized-Snapshot in einem `MAGIC_CHECKPOINT` (`0x5645474B`) + `CRC32C`-Envelope.  
   - Beim Neustart lädt die Engine den jüngsten gültigen Checkpoint und spielt lediglich die nachfolgenden WAL-Commits nach (`replayedWalCommits`), statt ab `c0` vollständig zu re-indizieren.

4. **Nicht-destruktive Kaltarchivierung & LRU-Cache (`archive/segment-<from>-<to>.wal.gz`, `P-07`)**  
   - `archiveHistoryBefore(cutoffCommitId)` schreibt historische Commits komprimiert ins Kaltarchiv, sichert einen Checkpoint bei `cutoffCommitId`, kompaktiert `wal.log` atomar auf die verbleibenden jüngeren Commits und verdrängt kalte Snapshots `< cutoffCommitId` (außer aktiven Branch-Heads) aus dem Heap.  
   - Spätere `asOf(...)`, `diff(...)` oder `replayAsOf(...)`-Abfragen auf archivierte Commits werden transparent über den jüngsten Vorgänger-Checkpoint + Delta-Replay im begrenzten LRU-Cache rekonstruiert — **kein referenzierter Commit geht verloren**.

## Recovery- & Fehlersemantik (`P-08`, `T17`)

- **Torn Tail Frame oder Tail-CRC-Fehler am Dateiende (`MID_WAL_FRAME_WRITE`, `CORRUPTED_WAL_TAIL_CRC`):** Wird beim Öffnen deterministisch am letzten Frame-Start abgeschnitten (`truncatedTailFrames`) und erhält alle zuvor abgeschlossenen Commits.
- **Absturz nach WAL-Flush vor `refs.meta`-Update (`AFTER_WAL_BEFORE_REFS_UPDATE`):** Der vollständige WAL-Commit wird beim Öffnen erkannt und der Ziel-Branch-Head automatisch nachgezogen (`recoveredUnindexedCommits`).
- **Verwaiste `*.tmp`-Dateien (`MID_CHECKPOINT_WRITE`):** Werden beim Öffnen bereinigt (`cleanedTempFiles`).
- **Korruption im Inneren (`wal.log` Non-Tail, `checkpoints/*`, `archive/*`, `refs.meta`):** Führt strikt zu einer `StorageCorruptionException` (`P-08`) statt stiller Datenverfälschung.
