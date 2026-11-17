# Forschungs- und Entwicklungslog (VEG)

Dieses Laborbuch dokumentiert gemäß Abschnitt 12 des Konzepts jede Entwicklungsstufe mit Hypothese, Implementierung, Tests, reproduzierbaren Messungen, Erkenntnissen und nächsten Schritten.

---

## Eintrag 01 — Stufe 0 & Stufe 1: Repo-Fundament, Minimalgraph & Bootstrap-Selbstbeschreibung

- **Datum:** 2026-11-17
- **Stufe / Hypothese:**  
  **Stufe 0** (Repo, Build-Disziplin, Test-Oracle, Baseline-Harness) + **Stufe 1** / **Hypothese H1, H2, H3**: Ein streng typisiertes, selbstbeschreibendes Graph-Metamodell lässt sich über einen sehr kleinen Bootstrap-Kern (`BootstrapIds`), first-class Attribut-Entities und first-class `Edge`-Entities (`EntityId` auf Kanten) abbilden, ohne Zirkularitätsprobleme oder Verlust strenger Typprüfung zu verursachen.

- **Implementiert:**  
  - Git-Repository auf `main` mit Apache-2.0-Lizenz, `.gitignore`, GitHub-Actions-CI (`.github/workflows/ci.yml` für Linux & Windows auf JDK 25), Maven Wrapper (`mvnw` / `mvnw.cmd`, Maven 3.9.15) und Multi-Modul-Build (`pom.xml` mit `<maven.compiler.release>25</maven.compiler.release>`).
  - `veg-model`: `EntityId`, `EntityKind`, `ValueType`, `Value` (`sealed interface` mit 6 skalaren Typen ohne implizite Konvertierung), `EntityNode`, `Edge`, `BootstrapIds` (IDs `1..33` für Meta-Typen, Skalartypen, Meta-Prädikate und Wildcards).
  - `veg-engine-api`: `GraphReader`, `GraphSession`, typisierte Exception-Hierarchie (`VegException`, `SchemaViolationException`, `TypeMismatchException`, `DomainRangeViolationException`, `DuplicateSchemaElementException`, `EntityNotFoundException`, `ReferentialIntegrityException`).
  - `veg-engine-memory`: `InMemoryGraphEngine` (Referenz-Engine A) inkl. selbstbeschreibendem Bootstrap im Graph, atomarer Validierung von Skalartyp, `HAS_DOMAIN`, `HAS_RANGE` sowie referenzieller Integrität bei `disconnect`.
  - `veg-test-fixtures`: Backend-unabhängige `Stage1ContractTestSuite` (`T01`–`T04` + Bootstrap- & Schema-Tests) und `DeterministicGraphGenerator`.
  - `veg-benchmarks` & `veg-cli`: `BaselineBenchmarkRunner` (CSV/JSON) und `VegCliMain` (`demo`, `benchmark`).
  - Dokumentation & ADRs: [GLOSSARY.md](GLOSSARY.md), [INVARIANTS.md](INVARIANTS.md), [ARCHITECTURE.md](ARCHITECTURE.md), [ADR-0001](adr/0001-semantic-assertions-on-relations.md), [ADR-0002](adr/0002-bootstrap-kernel-boundaries.md).

- **Tests (bestanden/fehlgeschlagen):**  
  `.\mvnw.cmd test` — **15 / 15 Tests bestanden, 0 fehlgeschlagen, 0 übersprungen**:
  - `veg-model` (`ModelPrimitivesTest`): 5 / 5 bestanden
  - `veg-engine-memory` (`InMemoryStage1ContractTest` für `T01`–`T04`, `GraphInvariantsProperties` mit `jqwik` `tries = 40`): 7 / 7 bestanden
  - `veg-benchmarks` (`BaselineBenchmarkRunnerTest`): 1 / 1 bestanden
  - `veg-cli` (`VegCliMainTest`): 2 / 2 bestanden

- **Messungen und Reproduzierbarkeit:**  
  Befehl:  
  `java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar benchmark --entities 10000 --seed 42 --shared-ratio 0.50 --provenance-ratio 0.25 --warmup 2 --iterations 5 --queries 2000 --output docs/experiments/stage0-baseline/results.csv --json docs/experiments/stage0-baseline/results.json`  
  Rohdaten: [results.csv](experiments/stage0-baseline/results.csv), [results.json](experiments/stage0-baseline/results.json), Detailanalyse: [analysis.md](experiments/stage0-baseline/analysis.md).  
  Kernergebnisse (OpenJDK 25.0.3, Windows 11 amd64, 12 CPUs, `InMemoryGraphEngine`, 69.203 Entities, 35.559 Edges):
  - **Write-Durchsatz (Iterationen 2–5 warm):** `2,14 – 2,28 Mio. ops/s` (`30,35 – 32,31 ms`)
  - **1-Hop Read:** `p50 = 600–800 ns`, `p99 = 1.400–2.700 ns`
  - **2-Hop Read (inkl. Provenienz-Edge):** `p50 = 600 ns`, `p99 = 1.500–2.500 ns`
  - **Heap-Bedarf (grobe GC-Delta-Schätzung):** `~14,57 – 14,70 MB` (`~220,8 – 222,7 Bytes/Entity`)

- **Was widerlegt / bestätigt wurde:**  
  - **Bestätigt (`H1`):** Ein Bootstrap-Kern aus 20 definierten Konstanten (`1..33`, zzgl. interner Bootstrap-Namen) genügt, um fachliche Typen (`Person`, `FirstName`, `SourceDocument`, `Confidence`) und Prädikate (`hasFirstName`, `supportedBy`, `hasConfidence`) vollständig als Graph-Daten zu halten und zur Laufzeit strikt zu prüfen.
  - **Bestätigt (`M-02` / `M-03`):** First-Class Edges (`EntityKind.EDGE`) erlauben Kanten-auf-Kanten-Annotation (`supportedBy`, `hasConfidence`) ohne Spezialtyp; explizites Sharing einer Attribut-Entity funktioniert mit isolierten Kantenlebenszyklen (`T03`).
  - **Bestätigt (Messhypothese 1, vorläufig):** Die naive Map-/Objekt-Repräsentation belegt bereits ohne Historie ~222 Bytes pro Entity — ein klarer quantitativer Anker für den späteren Vergleich mit Engine B und C in Stufe 7.

- **Risiken und technische Schulden:**  
  1. Monotoner globaler ID-Zähler (`nextUserEntityId++`) ist noch nicht branch-tauglich (in Stufe 4 umzustellen).
  2. `disconnect` löscht hart im Speicher statt über Tombstones/Deltas (in Stufe 3 zu versionieren).
  3. `HAS_DOMAIN` / `HAS_RANGE` prüfen bislang 1:1 auf Typ-ID oder Wildcard — Vererbung und Union-Constraints fehlen noch.
  4. Speichermessung im Baseline-Harness ist nur eine GC-Heap-Delta-Näherung, kein exaktes JOL-Layout.

- **Entscheidung (weiter / anpassen / verwerfen):**  
  **Weiter** mit Stufe 2. Die API-Trennung `GraphReader` / `GraphSession` und Engine A bleiben als Referenz-Oracle erhalten.

- **Nächster kleinster sinnvoller Versuch (Stufe 2):**  
  Experimentelle Klärung der Sharing- und Assertion-Semantik vor Einführung von Commits:
  1. Differenzierender Test zwischen *unveränderlichem Shared Value* vs. *explizit gemeinsam veränderbarer Attribut-Entity* (Shared Mutation Policy).
  2. Test- und Kostenvergleich **Option A** (`Connection + Assertions`) vs. **Option B** (`Edge = Assertion`, siehe [ADR-0001](adr/0001-semantic-assertions-on-relations.md)) bei konkurrierenden Aussagen zweier Quellen zwischen denselben Entitäten.
  3. Einführung von Kardinalitäts- und Uniqueness-Constraints auf Prädikaten als Graph-Daten.

---

## Eintrag 02 — Stufe 2: Identität, Sharing, Kardinalität & Assertion-Modell-Vergleich

- **Datum:** 2026-11-17
- **Stufe / Hypothese:**  
  **Stufe 2** / **Hypothesen H2 & H3**:  
  1. Explizite Trennung von `ValueMutability.IMMUTABLE` (Default) und `ValueMutability.SHARED_MUTABLE` verhindert versehentliche globale Seiteneffekte bei geteilten Attribut-Entities, erlaubt aber bewusste gemeinsame Updates.  
  2. Option B (`Edge = Assertion`) spart gegenüber Option A (`Connection + Assertions`) sowohl Speicher als auch Latenz, ohne Ausdrucksstärke für konkurrierende Aussagen, `weight` ≠ `confidence` und Provenienz einzubüßen.

- **Implementiert:**  
  - `veg-model`: `ValueMutability`, `PredicateConstraints` (`unconstrained`, `uniqueSet`, `manyToOne`, `oneToMany`, `oneToOne`), Erweiterung von `BootstrapIds` (`SYSTEM_INT_VALUE_TYPE`, `SYSTEM_BOOLEAN_VALUE_TYPE`, `HAS_MAX_OUTGOING`, `HAS_MAX_INCOMING`, `HAS_UNIQUE_TRIPLE`, `HAS_UNIQUE_VALUES`, `HAS_MUTABILITY`, `MUTABILITY_*`).
  - `veg-engine-api`: `ImmutableValueModificationException`, `CardinalityViolationException`, `UniquenessViolationException`, erweiterte Methoden auf `GraphReader` und `GraphSession` (`defineValueType(..., uniqueValues)`, `definePredicate(..., constraints)`, `createValue(..., mutability)`, `updateValue(...)`).
  - `veg-engine-memory`: Durchsetzung von `ValueMutability`, eindeutigen Werttypen (`uniqueValues`), `PredicateConstraints` sowie atomare Ablehnung von Verstößen in `InMemoryGraphEngine`.
  - `veg-test-fixtures`: `Stage2ContractTestSuite` + erweiterte `GraphInvariantsProperties`.
  - `veg-benchmarks` & `veg-cli`: `AssertionModelComparisonRunner` und CLI-Kommando `assertion-experiment`.
  - Dokumentation: Aktualisierung von [ADR-0001](adr/0001-semantic-assertions-on-relations.md), neu [ADR-0003](adr/0003-shared-value-mutation-and-cardinality.md), Erweiterung von [INVARIANTS.md](INVARIANTS.md) (`I-09`, `I-10`), [GLOSSARY.md](GLOSSARY.md), [ARCHITECTURE.md](ARCHITECTURE.md).

- **Tests (bestanden/fehlgeschlagen):**  
  `.\mvnw.cmd clean verify` — **24 / 24 Tests bestanden, 0 fehlgeschlagen, 0 übersprungen**:
  - `veg-model` (`ModelPrimitivesTest`): 6 / 6 bestanden
  - `veg-engine-memory`: 13 / 13 bestanden (`InMemoryStage1ContractTest` 6, `InMemoryStage2ContractTest` 5, `GraphInvariantsProperties` 2 mit `jqwik` `tries = 40`)
  - `veg-benchmarks` (`AssertionModelComparisonRunnerTest`, `BaselineBenchmarkRunnerTest`): 2 / 2 bestanden
  - `veg-cli` (`VegCliMainTest`): 3 / 3 bestanden

- **Messungen und Reproduzierbarkeit:**  
  Befehl:  
  `java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar assertion-experiment --pairs 5000 --seed 42 --densities 1,2,5 --warmup 1 --iterations 3 --queries 1000 --output docs/experiments/stage2-assertions/results.csv --json docs/experiments/stage2-assertions/results.json`  
  Rohdaten: [results.csv](experiments/stage2-assertions/results.csv), [results.json](experiments/stage2-assertions/results.json), Detailanalyse: [analysis.md](experiments/stage2-assertions/analysis.md).  
  Kernergebnisse (Iteration 3, warm):
  - **k = 1:** Option B spart **~33 % Heap/Aussage** (`2.221,70 B` vs. `3.304,31 B`) und ist bei Abfragen **~2,2× schneller** (`900 ns` vs. `2.000 ns` p50).
  - **k = 2:** Option B spart **~29 % Heap/Aussage** (`2.109,80 B` vs. `2.961,26 B`) und schreibt **~2,8× schneller** (`357.283` vs. `127.276 assert/s`).
  - **k = 5:** Option B bleibt **~19 % kompakter** (`2.167,96 B` vs. `2.670,76 B`) bei **~41 % niedrigerer p50-Abfragelatenz** (`1.900 ns` vs. `3.200 ns`).
  - **Semantische Äquivalenz:** Beide Modelle liefern in jedem Lauf identische Treffer (`142`, `130`, `406`).

- **Was widerlegt / bestätigt wurde:**  
  - **Bestätigt (`H2`):** Explizite Mutability-Semantik (`IMMUTABLE` vs. `SHARED_MUTABLE`) schützt geteilte Attribute sicher und bleibt konsistent mit eindeutigen Werttypen.
  - **Bestätigt (`H3` / `ADR-0001`):** `Edge = Assertion` schlägt `Connection + Assertions` über alle untersuchten Aussagedichten (`k = 1, 2, 5`).

- **Risiken und technische Schulden:**  
  1. Das Speichern einer expliziten `HAS_MUTABILITY`-Kante pro `VALUE`-Knoten erhöht den Entity-/Edge-Footprint in Engine A spürbar (in Stufe 7 in Engine B/C als kompaktes Bit-Flag im Entity-Header komprimierbar).
  2. Prädikate unterstützen noch keine Vererbung (`subPropertyOf` / Typvererbung `M-06`).
  3. Transaktionen, atomare Commits und historische Snapshots fehlen noch — aktuell mutiert `updateValue` / `disconnect` direkt im Live-Zustand.

- **Entscheidung (weiter / anpassen / verwerfen):**  
  **Weiter** mit Stufe 3. Option B (`Edge = Assertion`) und `ValueMutability` sind für den versionierten Kern freigegeben.

- **Nächster kleinster sinnvoller Versuch (Stufe 3):**  
  Einführung von Transaktions-Workspaces, Savepoints und unveränderlichen linearen Commits (`CommitId`, Parent, Actor, Reason, Timestamp) inklusive `asOf(commitId)`, `history(entityId)`, `diff(commitA, commitB)`, `revert(commitId)` sowie den Tests `T05`–`T09` und `T15` (Replay über 1.000 deterministische Mutationen).

---

## Eintrag 03 — Stufe 3: Atomare Commits, Savepoints, Historie, Diff, Revert & Replay

- **Datum:** 2026-11-17
- **Stufe / Hypothese:**  
  **Stufe 3** / **Hypothesen H1 (Commit-Envelope) & H4 (Append-only Historie + Snapshots)**:  
  1. Ein unveränderliches `Commit`-Envelope über 5 atomaren `Change`-Primitiven (`EntityCreated`, `EntityRemoved`, `ValueUpdated`, `EdgeConnected`, `EdgeDisconnected`) vermeidet zirkuläre Bootstrap-Abhängigkeiten und ermöglicht exakte Zustandsrekonstruktion per Replay (`T15`).  
  2. Isolierte Transaktions-Workspaces mit Savepoint-Stack sowie Schema-Evolution (`renameSchemaElement`, `updatePredicateConstraints`, `enableUniqueValues`) wahren jederzeit atomare Typ- und Bestandsintegrität (`T05`–`T08`).  
  3. Gegen-Commit-Revert (`T09`) bewahrt die lückenlose Audit-Historie und erkennt Folgekonflikte deterministisch.

- **Implementiert:**  
  - `veg-model`: `CommitId`, `Actor`, `Reason`, `SavepointId`, `Change` (`sealed interface` mit 5 Varianten), `Commit`, `GraphDiff`.
  - `veg-engine-api`: `GraphTransaction` (`AutoCloseable` mit Savepoints), `VersionedGraphDatabase`, Schema-Evolution auf `GraphSession` (`renameSchemaElement`, `updatePredicateConstraints`, `enableUniqueValues`), `allEntities()`/`allEdges()` auf `GraphReader`, Exceptions (`TransactionStateException`, `CommitNotFoundException`, `RevertConflictException`).
  - `veg-engine-memory`: Modularisierung in `MutableGraphState`, `AbstractGraphReader`, `BootstrapKernelInitializer`, `RecordedMutationSink`, `SchemaOperationsHandler`, `GraphSchemaValidator`, `InMemoryGraphEngine` (221 Zeilen), `InMemoryGraphSnapshot`, `InMemoryTransaction`, `GraphDiffCalculator`, `InMemoryVersionedDatabase`.
  - `veg-test-fixtures` & Tests: `Stage3ContractTestSuite` (23 Testmethoden für `T05`–`T09`, `T15`, Savepoints, `diff`, `historyOf`), `VersionedDatabaseProperties` (`jqwik`, `tries = 30`), `VersionedModelPrimitivesTest`.
  - `veg-cli`: Erweiterung von `demo` um Stufe-3-Commit-/Savepoint-/Diff-/Revert-/Replay-Ablauf.
  - Dokumentation: [ADR-0004](adr/0004-transaction-workspace-and-commit-log.md), Erweiterung von [INVARIANTS.md](INVARIANTS.md) (`I-11`–`I-13`) und [ARCHITECTURE.md](ARCHITECTURE.md).

- **Tests (bestanden/fehlgeschlagen):**  
  `.\mvnw.cmd clean verify` — **52 / 52 Tests bestanden, 0 fehlgeschlagen, 0 übersprungen**:
  - `veg-model`: 10 / 10 bestanden (`ModelPrimitivesTest` 6, `VersionedModelPrimitivesTest` 4)
  - `veg-engine-memory`: 37 / 37 bestanden (`InMemoryStage1ContractTest` 6, `InMemoryStage2ContractTest` 5, `InMemoryStage3ContractTest` 23, `GraphInvariantsProperties` 2 × `tries = 40`, `VersionedDatabaseProperties` 1 × `tries = 30`)
  - `veg-benchmarks`: 2 / 2 bestanden
  - `veg-cli`: 3 / 3 bestanden

- **Messungen und Reproduzierbarkeit:**  
  Reproduzierbarkeit über `T15` (`t15ReplayFromCommitZeroThrough1000DeterministicMutationsMatchesLiveSnapshotExactly`, Seed `42`, > 1.050 Mutationen inkl. Schema-Umbenennung über mehr als 40 Commits) und `VersionedDatabaseProperties` (`tries = 30`): `replayAsOf(c)` liefert für jeden Commit `c0..cHead` exakt identische Entitäten, Kanten und Constraints wie `asOf(c)`.

- **Was widerlegt / bestätigt wurde:**  
  - **Bestätigt (`H1` / `ADR-0004`):** Das `Commit`-Envelope-Modell vermeidet Selbstreferenz-Zyklen und erlaubt vollständigen Replay ohne Verlust von `entityCount()`-Klarheit.
  - **Bestätigt (`M-07` / `T05` / `T06`):** Schema-Evolution (Umbenennung mit stabiler `EntityId`, Verschärfung von Kardinalität und Wert-Eindeutigkeit gegen Bestandsdaten) funktioniert historisch nachvollziehbar innerhalb desselben Transaktionsmodells.
  - **Bestätigt (`T09`):** Konfliktbehaftete Reverts (spätere abhängige Edges oder spätere Wertänderungen) lassen sich präzise abweisen.

- **Risiken und technische Schulden:**  
  1. `InMemoryVersionedDatabase` speichert aktuell pro Commit einen vollen Snapshot (`MutableGraphState.copy()`) für `O(1)`-`asOf` — in Stufe 4/7 für große Bestände und viele Branches auf strukturelle Teilung (`T16`) umzustellen.
  2. `nextUserEntityId` ist noch ein linearer Zähler (für parallele Branches in Stufe 4 anzupassen).
  3. Commit-Historie ist bislang rein linear (`parentId: Optional<CommitId>`), noch kein Branch-DAG mit 3-Wege-Merge.

- **Entscheidung (weiter / anpassen / verwerfen):**  
  **Weiter** mit Stufe 4 (Branches, selektives Staging & struktureller 3-Wege-Merge).

- **Nächster kleinster sinnvoller Versuch (Stufe 4):**  
  Übergang vom linearen Commit-Log auf einen Branch-/Tag-DAG mit strukturell geteilten Zuständen (`T16`), selektivem Staging mit Abhängigkeitsprüfung (`V-06`) sowie Drei-Wege-Diff/Merge inklusive Konflikterkennung für Daten, gemeinsam genutzte Wertobjekte (`T11`) und Schemaänderungen (`T10`, `T12`).

---

## Eintrag 04 — Stufe 4: Branches, selektives Staging & struktureller Drei-Wege-Merge

- **Datum:** 2026-11-17
- **Stufe / Hypothese:**  
  **Stufe 4** / **Hypothesen H4 & H5**:  
  1. Ein Branch-Zeiger-Modell auf unveränderlichen Snapshots samt globalem kollisionsfreiem `EntityId`-Allokator erlaubt hunderte Branches ohne Objektkopien (`T16`) und konfliktfreie Merges unabhängiger Schema-/Datenänderungen (`T10`).  
  2. Gruppiertes selektives Staging verhindert das versehentliche Committen von Kanten oder Objekten ohne ihre erforderlichen Endpunkte oder Typdefinitionen (`V-06`).  
  3. Struktureller Drei-Wege-Merge mit `DataCategory`-Filterung schützt Entwicklungsmerges vor operativen Betriebsdaten (`V-13`) und meldet geteilte Wertkonflikte (`T11`) sowie Schema-vs.-Daten-Konflikte (`T12`) explizit statt per Last-Writer-Wins.

- **Implementiert:**  
  - `veg-model`: `Commit` (erweitert um `parentIds`, `branchName`, `dataCategory` bei voller Rückwärtskompatibilität), `DataCategory`, `Branch`, `Tag`, `MergePolicy`, `MergeConflictType`, `MergeConflict`, `MergeResult`.
  - `veg-engine-api`: `StagingWorkspace`, erweiterte `VersionedGraphDatabase` (`createBranch`, `checkoutBranch`, `createTag`, `asOfTag`, `createStagingWorkspace`, `findCommonAncestor`, `merge`, `resolveMerge`), Exceptions (`BranchNotFoundException`, `DuplicateBranchOrTagException`, `MissingDependencyException`, `MergeConflictException`).
  - `veg-engine-memory`: `InMemoryStagingWorkspace`, `ThreeWayMergeEngine`, `CommitReverter`, gemeinsamer `AtomicLong`-Allokator in `MutableGraphState` sowie DAG-fähiges `InMemoryVersionedDatabase` (inkl. First-Parent-Replay über Merge-Commits).
  - `veg-test-fixtures` & Tests: `Stage4ContractTestSuite` (22 Testmethoden für `V-05`–`V-08`, `V-12`–`V-13`, `T10`–`T12`, `T16`), `BranchMergeProperties` (`jqwik`, `tries = 25`), `BranchAndMergeModelTest`.
  - `veg-cli`: Erweiterung von `demo` um Stufe-4-Branching, selektives Staging, `OPERATIONAL`-Datenfilterung und 3-Wege-Merge.
  - Dokumentation: [ADR-0005](adr/0005-branching-selective-staging-and-three-way-merge.md), Erweiterung von [INVARIANTS.md](INVARIANTS.md) (`I-14`–`I-16`), [ARCHITECTURE.md](ARCHITECTURE.md).

- **Tests (bestanden/fehlgeschlagen):**  
  `.\mvnw.cmd clean verify` — **78 / 78 Tests bestanden, 0 fehlgeschlagen, 0 übersprungen**:
  - `veg-model`: 13 / 13 bestanden (`BranchAndMergeModelTest` 3, `ModelPrimitivesTest` 6, `VersionedModelPrimitivesTest` 4)
  - `veg-engine-memory`: 60 / 60 bestanden (`InMemoryStage1ContractTest` 6, `InMemoryStage2ContractTest` 5, `InMemoryStage3ContractTest` 23, `InMemoryStage4ContractTest` 22, `BranchMergeProperties` 1 × `tries = 25`, `GraphInvariantsProperties` 2 × `tries = 40`, `VersionedDatabaseProperties` 1 × `tries = 30`)
  - `veg-benchmarks`: 2 / 2 bestanden
  - `veg-cli`: 3 / 3 bestanden

- **Messungen und Reproduzierbarkeit:**  
  - `T16`: 500 Branches auf einem mit `DeterministicGraphGenerator` (500 Personen) gefüllten Graphen teilen ohne jede Datenkopie dieselbe `InMemoryGraphSnapshot`-Instanz.
  - `BranchMergeProperties` (`tries = 25`): Kommutativität unabhängiger Branch-Merges (`merge(A -> main); merge(B -> main)` == `merge(B -> main); merge(A -> main)`) sowie identischer `replayAsOf(mergeCommitId)`.

- **Was widerlegt / bestätigt wurde:**  
  - **Bestätigt (`V-06` / `V-13` / `T10`–`T12`):** Selektives Staging mit `MissingDependencyException`, die Trennung von `REFERENCE`/`SCHEMA` vs. `OPERATIONAL`/`TEST` im Merge sowie alle 6 Konfliktklassen arbeiten deterministisch zusammen.
  - **Wichtiges Architektur-Detail:** Beim Replay auf einem Commit-DAG darf `replayAsOf(c)` für Merge-Commits nur der First-Parent-Linie folgen, weil der Merge-Commit selbst bereits das effektive Delta auf den Ziel-Branch enthält.

- **Risiken und technische Schulden:**  
  1. Pro Commit hält Engine A in `snapshots` weiterhin eine eigene `MutableGraphState`-Kopie — echte Sub-Graph-Teilung zwischen Commits folgt in Stufe 7 (Engine C).
  2. Berechtigungen (Entity/Relation/Attribut) und semantische Ableitungen fehlen noch.

- **Entscheidung (weiter / anpassen / verwerfen):**  
  **Weiter** mit Stufe 5 (Berechtigungen und semantische Regeln).

- **Nächster kleinster sinnvoller Versuch (Stufe 5):**  
  Versions- und branchbewusste Zugriffskontrolle auf Entity-, Relations- und Attributebene (`S-01`–`S-03`, `S-05`), deklarative Rechtevererbung entlang explizit freigegebener Relationstypen mit Default-Deny und Zyklusschutz (`T13`, `T14`) sowie minimale abgeleitete Aussagen (`inverse` / `subPropertyOf`, klar getrennt von expliziten Aussagen, `M-12`).
