# ADR-0010: Multi-User MVCC, automatische Typ-Indizes, Bitemporalität, Cherry-Pick & DSGVO-Redaktion

- **Status:** Akzeptiert (Stufe 10)
- **Datum:** 2026-11-17

## Kontext

Nach erfolgreicher Disk-Persistenz und Crash-Recovery in Stufe 8 ([ADR-0009](0009-persistent-wal-checkpoints-and-crash-recovery.md)) adressiert **Stufe 10** die zentralen Produktions- und Fachanforderungen von [Versioned_Entity_Graph_RnD_Konzept.md](../../doc/Versioned_Entity_Graph_RnD_Konzept.md):
- **Mehrbenutzer-Konkurrenz & Retry-Idempotenz (`V-03`, `V-15`)**
- **Automatische Indizes & ACL-Caching (`P-05`)**
- **Bitemporale Abfragen (`V-14`):** Systemzeit (Commit-Zeitpunkt) vs. fachliche Gültigkeitszeit `[validFrom, validTo)`
- **Selektiver Cherry-Pick (`V-16`)**
- **Revisionssichere Payload-Redaktion für Datenschutz / Aufbewahrungsfristen (`S-09`)**

## Architekturentscheidungen

### 1. MVCC-Konkurrenz, `OptimisticCommitConflictException` & Idempotenz
- **Locking-Modell:** Alle vier Storage-Engines (`InMemoryVersionedDatabase`, `CompactVersionedDatabase`, `PersistentTreeVersionedDatabase` sowie `PersistentDiskVersionedDatabase` via Delegate) trennen über `ReentrantReadWriteLock` kurz laufende Lese-Lookups von schreibenden Metadaten-/Commit-Mutationen. Einmal gelieferte Snapshots sind vollständig unveränderlich und lock-frei lesbar; offene Transaktionen halten niemals Schreibsperren während ihrer Benutzerlogik.
- **Optimistische Konflikte & Retry (`executeWithRetry`):** Bewegt ein paralleler Schreiber den Ziel-Branch zwischen `beginTransaction` und `commit()`, wirft die Engine eine typisierte `OptimisticCommitConflictException` (Unterklasse von `TransactionStateException`) mit `branchName`, `expectedBaseCommitId` und `actualHeadCommitId`. `executeWithRetry` wiederholt die Transaktion automatisch bis `maxAttempts`.
- **Idempotenz (`executeIdempotent` / `IdempotentCommitSupport`):** Mit 64-fach gestripten Locks pro `(branchName, idempotencyKey)` führt VEG wiederholte oder gleichzeitig racende Retries höchstens einmal aus und persistiert die Zuordnung `compositeKey -> CommitId` in `refs.meta` (über Neustarts hinweg erhalten).
- **Korrelations-Audit (`V-15`):** `commitsByCorrelationId(correlationId)` fasst verteilte Transaktionen eines fachlichen Vorgangs lückenlos zusammen.

### 2. Automatische Typ-Indizes & Snapshot-ACL-Memoization (`P-05`)
- **Sekundärindex ohne manuelle Indexplanung:** `GraphReader.findEntitiesByType(typeId)` ist in allen Snapshot-Formaten nativ hinterlegt (`MutableGraphState.entitiesByType`, `CompactGraphSnapshot` via bestehendes CSR-Verfahren und `PersistentGraphSnapshot` über einen strukturell geteilten `PersistentLongMap<List<EntityNode>>`). `GraphQuery` nutzt diesen Index automatisch in `MATCH (a:Type)`.
- **ACL-Memoization (`SnapshotAclCache`):** Da Snapshots unveränderlich sind, cacht jeder Snapshot `evaluateAccess(principalId, permission, targetId)` threadsicher per `ConcurrentHashMap`.

### 3. Bitemporalität im Graphmodell (`V-14`)
- **Repräsentation im Graph:** `BootstrapIds.SYSTEM_TIMESTAMP_VALUE_TYPE` (`#9`), `BootstrapIds.VALID_FROM` (`#70`) und `BootstrapIds.VALID_TO` (`#71`) erweitern den selbstbeschreibenden Bootstrap-Kern.
- **Halboffenes Intervall `[validFrom, validTo)`:** `ValidityWindow` validiert `validFrom < validTo`. `GraphReader.validAt(instant)` liefert eine `BitemporalGraphReader`-Projektion, die Entitäten und Kanten (Kante sichtbar iff Kante, Source und Target gültig) filtert — kombinierbar mit `asOfBitemporal(commitId, validTime)`, `asOfSystemAndValidTime(branch, systemTime, validTime)`, `executeQuery` und `AuthorizedGraphReader`.

### 4. Selektiver Cherry-Pick (`V-16`) & DSGVO-Redaktion (`S-09`)
- **`cherryPick(sourceCommitId, targetBranch, ...)`:** Bereitet über `ThreeWayMergeEngine.prepareCherryPick` exakt das Delta relativ zum Parent des Quell-Commits auf den Ziel-Branch auf — inklusive aller 6 strukturellen Konfliktdetektoren und Einzel-Parent-Commit-Historie `[targetHead]`.
- **`redactEntityPayload(valueEntityId, actor, reason)` (`RedactionEngine`):** Ersetzt sensitive `VALUE`-Payloads typtreu (`"[REDACTED]"`, `0L`, `BigDecimal.ZERO`, `false`, `byte[0]`, `Instant.EPOCH`), schreibt betroffene Commits, Snapshots, `ValueInterner`-Caches sowie in `PersistentDiskVersionedDatabase` auch WAL, Checkpoints, Kaltarchive und `refs.meta` neu um und protokolliert einen kryptographischen SHA-256-Nachweis (`RedactionReceipt`) — bei vollem Erhalt aller `EntityId`s, `Edge`s, `CommitId`s und historischen Replays. Schema-/Bootstrap-Metadaten bleiben gegen Redaktion gesperrt (`SchemaViolationException`).
