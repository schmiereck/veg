# ADR-0004: Transaktions-Workspace, Commit-Envelope, Savepoints & Revert

- **Status:** Akzeptiert (Stufe 3)
- **Datum:** 2026-11-17
- **Kontext:** Hypothesen `H1` (offene Frage zu Commit-Metadaten) & `H4`, Anforderungen `V-01`–`V-04`, `V-09`–`V-11`, `M-07`–`M-08`, `T05`–`T09`, `T15`

## Problemstellung

Für Stufe 3 sind folgende Fragen zu klären:
1. **Commit-Metadaten (`H1` offene Frage):** Sollen Commit-Metadaten (`CommitId`, `parentId`, `actor`, `reason`, `correlationId`, `timestamp`) bereits als reguläre Graph-Entities gespeichert werden oder als physisch-strukturelles Commit-Envelope außerhalb des Graphen?
2. **Transaktionsisolation & Savepoints (`V-03`, `V-11`):** Wie bleiben offene Schreiboperationen unsichtbar und wie funktionieren verschachtelte Savepoints?
3. **Replay & Gegen-Commit-Revert (`T09`, `T15`):** Welche `Change`-Primitiven sind nötig, damit `replayAsOf(c)` exakt `asOf(c)` rekonstruiert und `revert(c)` konsistent und konfliktfrei bleibt?

## Entscheidung

### 1. Commit als unveränderliches Envelope über atomaren `Change`-Primitiven
- `Commit` (`CommitId`, `parentId`, `Actor`, `Reason`, `correlationId`, `timestamp`, `List<Change>`) bildet ein unveränderliches Commit-Envelope außerhalb der Daten-Entities.
- **Begründung (`H1` beantwortet):** Ein Ablegen von `Commit`-Metadaten als reguläre Graph-Entities beim atomaren Schreiben würde zirkuläre Abhängigkeiten erzeugen (*ein Commit müsste eine Commit-Entity innerhalb seines eigenen Änderungssatzes protokollieren*), sowie `entityCount()`/`edgeCount()` und Typ-Constraints bei jedem fachlichen Commit mit System-Metadaten überlagern. Durch das Envelope-Modell bleibt `c0..cN` sauber, unveränderlich und reproduzierbar replaybar; künftige Query-Adapter (Stufe 6) können Commits bei Bedarf dennoch als virtuelle Graph-Sicht projizieren.

### 2. Fünf replayfähige `Change`-Primitiven
Die `sealed interface Change` beschreibt jeden Zustandsübergang exakt:
- `EntityCreated(EntityNode)` (für `META_TYPE`, `SCALAR_TYPE`, `OBJECT`, `VALUE`)
- `EntityRemoved(EntityNode)` (z. B. beim Entfernen eines Constraint-Flags oder beim Revert eines `EntityCreated`)
- `ValueUpdated(EntityId, previousValue, newValue)` (inkl. `SHARED_MUTABLE`-Updates und Schema-Umbenennungen `renameSchemaElement`)
- `EdgeConnected(Edge)` (erzeugt Edge + zugehörige `EntityKind.EDGE`-Node)
- `EdgeDisconnected(Edge)` (entfernt Edge + zugehörige `EntityKind.EDGE`-Node)

### 3. Transaktions-Workspace mit Savepoint-Stack & Kopier-Isolation
- `InMemoryTransaction` arbeitet auf einer tiefen Kopie von `MutableGraphState` und protokolliert jeden Schritt in `pendingChanges`.
- Savepoints (`SavepointId`) sichern `(MutableGraphState.copy(), pendingChanges.size())` auf einem Stack. Ein Rollback zu einem äußeren Savepoint verwirft alle inneren Savepoints (`TransactionStateException` bei späterer Verwendung).
- Unveränderliche Snapshots (`InMemoryGraphSnapshot`) und laufende Transaktionen stören einander nicht; ein Schließen (`try-with-resources`) ohne `commit()` rollt automatisch zurück (`T07`).

### 4. Deterministischer Gegen-Commit beim `revert` (`V-10`, `T09`)
- `revert(targetCommitId, ...)` löscht niemals Historie, sondern wendet die `targetCommit.changes()` in **umgekehrter Reihenfolge** als neuen Gegen-Commit auf den aktuellen `head` an.
- Konflikte werden atomar mit `RevertConflictException` abgewiesen, wenn z. B.:
  - `c0` (`CommitId.BOOTSTRAP`) revertiert werden soll,
  - eine erzeugte Entity/Edge inzwischen von nachfolgenden Kanten oder Subtypen referenziert wird,
  - ein aktualisierter Wert durch einen späteren Commit erneut überschrieben wurde,
  - eine wiederherzustellende Kante aktuelle Kardinalitäts-/Uniqueness-Constraints verletzt oder eine wiederhergestellte Schema-Umbenennung auf einen inzwischen vergebenen Namen prallt.
