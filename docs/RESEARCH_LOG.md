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

---

## Eintrag 05 — Stufe 5: Versionsbewusste Zugriffskontrolle ("Verbieten gewinnt") & abgeleitete Aussagen

- **Datum:** 2026-11-17
- **Stufe / Hypothese:**  
  **Stufe 5** (`S-01`–`S-03`, `S-05`, `M-12`, `T13`, `T14`) / **"Verbieten gewinnt" (Deny Wins) & Default Deny**:  
  1. Eine rein im Graph modellierte Zugriffskontrolle (`META_PRINCIPAL_TYPE`, `META_ACL_ENTRY_TYPE`, `META_PROPAGATION_RULE_TYPE`) mit globalem "Verbieten gewinnt" ohne Kurzschluss auf `ALLOW` macht Rechte über Gruppen, Typ-Scopes und propagierende Relationstypen hinweg vollständig versionier-, merge- und erklärbar (`AccessDecision`, `S-03`).  
  2. Zeitreisensichere Sichten (`AuthorizedGraphReader` auf `asOf(commitId)`) verhindern durch eine Rückwärtsprüfung gegen den aktuellen `HEAD` sowie Branch-Restriktionen (`S-05`) nachträgliches Auslesen widerrufener historischer Stände (`T14`).  
  3. Semantische Prädikatregeln (`HAS_INVERSE_PREDICATE`, `HAS_SUPER_PREDICATE`) lassen sich zyklussicher zur Abfragezeit als `DerivedAssertion` (`AssertionOrigin`) auswerten, ohne explizite Kantenbestände (`findOutgoingEdges`) zu verunreinigen (`M-12`).

- **Implementiert:**  
  - `veg-model`: `BootstrapIds` (`50..52`, `60..69`), `Permission`, `AccessEffect`, `PropagationDirection`, `AccessReason`, `AccessDecision`, `AssertionOrigin`, `DerivedAssertion`.
  - `veg-engine-api`: `AuthorizedGraphReader`, `AccessDeniedException`, erweiterte `GraphReader` (`findPrincipalByName`, `evaluateAccess`, `findDerivedOutgoingAssertions`, `findAllOutgoingAssertions`), `GraphSession` (`definePrincipal`, `addGroupMember`, `grantPermission`, `denyPermission`, `definePermissionPropagation`, `defineInversePredicate`, `defineSubPredicate`) und `VersionedGraphDatabase` (`restrictBranchReadAccess`, `authorizedCurrentSnapshot`, `authorizedBranchSnapshot`, `authorizedAsOf`).
  - `veg-engine-memory` (modular ausgelagert statt Erweiterung von `InMemoryGraphEngine`): `AccessControlOperationsHandler`, `PermissionEvaluator`, `SemanticInferenceEngine`, `AuthorizedGraphReaderImpl`, dedizierter `principalByName`-Index in `MutableGraphState`, Erweiterung von `BootstrapKernelInitializer`, `GraphSchemaValidator`, `AbstractGraphReader`, `InMemoryTransaction`, `InMemoryStagingWorkspace` und `InMemoryVersionedDatabase`.
  - `veg-test-fixtures` & Tests: `SecurityAndInferenceModelTest` (3 Tests), `Stage5ContractTestSuite` / `InMemoryStage5ContractTest` (22 Contract-Tests für `S-01`–`S-03`, `S-05`, `M-12`, `T13`, `T14`, Zyklen, Kanten-/Attribut-ACLs, `ADMIN` vs. `DENY WRITE`, Savepoints/Revert/Merge/Staging), `AccessControlProperties` (`jqwik`, 2 Properties × `tries = 60`).
  - `veg-cli`: `runStage5Demo` in `VegCliMain` + Erweiterung von `VegCliMainTest`.
  - Dokumentation: [ADR-0006](adr/0006-access-control-deny-wins-and-semantic-inference.md), Erweiterung von [INVARIANTS.md](INVARIANTS.md) (`I-17`–`I-19`), [ARCHITECTURE.md](ARCHITECTURE.md), [CONCEPT.md](CONCEPT.md).

- **Tests (bestanden/fehlgeschlagen):**  
  `.\mvnw.cmd clean verify` — **105 / 105 Tests bestanden, 0 fehlgeschlagen, 0 übersprungen**:
  - `veg-model`: 16 / 16 bestanden (`BranchAndMergeModelTest` 3, `ModelPrimitivesTest` 6, `SecurityAndInferenceModelTest` 3, `VersionedModelPrimitivesTest` 4)
  - `veg-engine-memory`: 84 / 84 bestanden (`InMemoryStage1ContractTest` 6, `InMemoryStage2ContractTest` 5, `InMemoryStage3ContractTest` 23, `InMemoryStage4ContractTest` 22, `InMemoryStage5ContractTest` 22, `AccessControlProperties` 2 × `tries = 60`, `BranchMergeProperties` 1 × `tries = 25`, `GraphInvariantsProperties` 2 × `tries = 40`, `VersionedDatabaseProperties` 1 × `tries = 30`)
  - `veg-benchmarks`: 2 / 2 bestanden
  - `veg-cli`: 3 / 3 bestanden

- **Messungen und Reproduzierbarkeit:**  
  - `java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar demo`: Exit-Code 0 (`Alice read Doc via propagation=true (INHERITED_ALLOW, writeAllowed=false)`, `Alice salaryVal hidden=true, Bob time-travel after revoke allowed=false (HISTORICAL_HEAD_DENY)`, `Doc explicit outgoing=0, derived inverse count=1 (DERIVED_INVERSE)`).
  - `AccessControlProperties` (`tries = 60` auf zufälligen zyklischen Graphen): Deterministische Terminierung und übereinstimmendes `replayAsOf`-Ergebnis sowie Monotonie von `DENY` trotz beliebig vieler nachgelagerter `ALLOW`-Regeln bestätigt.

- **Was widerlegt / bestätigt wurde:**  
  - **Bestätigt:** "Verbieten gewinnt" erfordert in Graphen mit Gruppen- und Kantenvererbung, dass selbst bei einem direkten `ALLOW` alle erreichbaren Propagationspfade weiter auf `DENY` geprüft werden — andernfalls könnten übergeordnete Verbote umgangen werden.
  - **Wichtiges Modell-Detail:** Da in VEG jede Kante auch als `EntityKind.EDGE`-Knoten in `entities` repräsentiert wird, liefert `AuthorizedGraphReader.allEntities()` berechtigterweise auch lesbare Kanten-Entities, während gesperrte Kanten und Attributwerte konsequent ausgefiltert werden.

- **Risiken und technische Schulden:**  
  1. Bei sehr tiefen ACL-Hierarchien evaluiert `PermissionEvaluator` aktuell pro Abfrage dynamisch (BFS); Engine B/C (Stufe 7) sollte optionales Caching/Indizierung prüfen.
  2. Eine Query-DSL sowie der RDF-/Turtle-Export (`T18`) folgen in Stufe 6.

- **Entscheidung (weiter / anpassen / verwerfen):**  
  **Weiter** mit Stufe 6 (Storage-unabhängige Query-API/DSL & RDF-Export).

- **Nächster kleinster sinnvoller Versuch (Stufe 6):**  
  Entwurf einer storage-unabhängigen Graph-Query-/Traversierungs-API (inkl. abgeleiteter Aussagen und `AuthorizedGraphReader`-Integration) sowie RDF-/Turtle-Export (`T18`).

---

## Eintrag 06 — Stufe 6: Storage-unabhängige Query-API/DSL, RDF/Turtle-Interoperabilität & LUBM/BSBM-Datensätze

- **Datum:** 2026-11-17
- **Stufe / Hypothese:**  
  **Stufe 6** (`S-04`, `S-06`, `S-07`, `P-01`, `M-12`, `T18`):  
  1. Eine strikt gegen `GraphReader` definierte Fluent-Query-API (`GraphQuery`) und kompakte deklarative DSL (`GraphQueryDsl`) erlaubt typsichere Filter, Joins, Traversierungen, Sortierungen und Paging identisch über Live-Snapshots, Feature-Branches, `asOf` sowie `AuthorizedGraphReader`-Sichten hinweg.  
  2. Standard-RDF-Datensätze mittlerer Komplexität (**LUBM** und **BSBM** im Turtle-Format) inklusive `rdfs:subPropertyOf`, `owl:inverseOf` und `rdf:Statement`-Provenienz (`hasWeight`, `hasConfidence`, `supportedBy`) lassen sich verlustfrei importieren, abfragen und per Turtle/N-Triples-Export wieder reproduzieren (`T18`).

- **Implementiert:**  
  - `veg-model`: `ComparisonOperator`, `SortDirection`, `SortOrder`, `QueryRow`, `RdfFormat`, `RdfExportOptions`, `RdfExportResult`, `RdfImportResult`, Unit-Tests in `QueryAndRdfModelTest` (3 Tests).
  - `veg-engine-api`: `GraphQuery`, `GraphQueryDsl`, `QuerySyntaxException`, `RdfGraphExporter`, `RdfGraphImporter`, Convenience-Default-Methoden auf `GraphReader` (`query`, `executeQuery`, `exportRdf`, `exportTurtle`) und `GraphSession` (`importRdf`, `importTurtle`).
  - `veg-test-fixtures` & Tests: Bundled Standard-Turtle-Benchmarks `rdf/lubm-university.ttl` (42 Fachobjekte mit `chairOf -> headOf -> worksFor -> memberOf`, `hasAlumnus owl:inverseOf degreeFrom`) und `rdf/bsbm-ecommerce.ttl` (32 Fachobjekte inkl. 2 reifizierter `rdf:Statement`-Provenienzblöcke), `RdfBenchmarkDatasets`, `Stage6ContractTestSuite` / `InMemoryStage6ContractTest` (10 Contract-Tests inkl. `AuthorizedGraphReader`-Schutz, LUBM-Inferenzabfragen, BSBM-Angebotsanalyse und `T18` Turtle/N-Triples Round-Trip & Warnung bei `includeReifiedProvenance=false`).
  - `veg-cli`: Erweiterung von `VegCliMain` um `runStage6Demo` sowie den neuen CLI-Befehl `import-rdf --input ... [--format turtle|ntriples] [--query ...] [--export ...]`; Tests in `VegCliMainTest` (4 Tests).
  - Dokumentation: [ADR-0007](adr/0007-query-dsl-and-rdf-turtle-interoperability.md), Erweiterung von [INVARIANTS.md](INVARIANTS.md) (`I-20`–`I-21`), [ARCHITECTURE.md](ARCHITECTURE.md), [CONCEPT.md](CONCEPT.md), [README.md](README.md).

- **Tests (bestanden/fehlgeschlagen):**  
  `.\mvnw.cmd clean verify` — **119 / 119 Tests bestanden, 0 fehlgeschlagen, 0 übersprungen**:
  - `veg-model`: 19 / 19 bestanden (`BranchAndMergeModelTest` 3, `ModelPrimitivesTest` 6, `QueryAndRdfModelTest` 3, `SecurityAndInferenceModelTest` 3, `VersionedModelPrimitivesTest` 4)
  - `veg-engine-memory`: 94 / 94 bestanden (`InMemoryStage1ContractTest` 6, `InMemoryStage2ContractTest` 5, `InMemoryStage3ContractTest` 23, `InMemoryStage4ContractTest` 22, `InMemoryStage5ContractTest` 22, `InMemoryStage6ContractTest` 10, `AccessControlProperties` 2 × `tries = 60`, `BranchMergeProperties` 1 × `tries = 25`, `GraphInvariantsProperties` 2 × `tries = 40`, `VersionedDatabaseProperties` 1 × `tries = 30`)
  - `veg-benchmarks`: 2 / 2 bestanden
  - `veg-cli`: 4 / 4 bestanden

- **Messungen und Reproduzierbarkeit:**  
  - `java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar demo`: Exit-Code 0 (`LUBM imported objects=42, Lehigh professors inferred via USING DERIVED memberOf=5 (top=Prof. Grace Hopper)`, `BSBM imported objects=32, top in-stock offer=Fiber Patch Analyzer F1 @ 189.95`, `T18 Turtle export triples=248, reifiedAnnotations=2, warnings=0`).
  - `T18`-Re-Import sowohl von Turtle als auch von N-Triples rekonstruiert alle `6` Kanten-zu-Kanten-Provenienz-Annotationen (`hasWeight`, `hasConfidence`, `supportedBy`) sowie die explizit/abgeleitet-Trennung (`veg:assertionOrigin`, `M-12`).

- **Was widerlegt / bestätigt wurde:**  
  - **Bestätigt (`P-01` / `S-04` / `S-05`):** Weil `GraphQuery`, `GraphQueryDsl` und `RdfGraphExporter` ausschließlich gegen `GraphReader` arbeiten, erben alle Abfragen und Exporte ohne Sonderlogik den vollen `AuthorizedGraphReader`-Schutz (keine Lecks geheimer Attributwerte in `WHERE`, `SELECT` oder `.ttl`-Exporten).
  - **Parser-Detail bei Turtle:** Dezimale Literale (`4.8`, `3.92`) erfordern im Turtle-Lexer die Unterscheidung zwischen Dezimalpunkt (folgt eine Ziffer) und Statement-Ende-Punkt (folgt Whitespace/Kommentar/EOF).

- **Risiken und technische Schulden:**  
  1. Komplexe Query-Pläne werten `JOIN`s aktuell naiv über die Engine-API aus; Leistungsvergleich und Optimierungsbedarf werden in Stufe 7 gemessen.
  2. Alle Daten leben bisher rein im Hauptspeicher (`InMemoryVersionedDatabase`) — kompakte Storage-Formate (`Engine B`/`C`) und Disk-Persistenz folgen in den Stufen 7–8.

- **Entscheidung (weiter / anpassen / verwerfen):**  
  **Weiter** mit Stufe 7 (Storage-Experimente & Benchmarks: Engine A vs. B vs. C).

- **Nächster kleinster sinnvoller Versuch (Stufe 7):**  
  Konzeption und Benchmark-Vergleich einer kompakten In-Memory-Repräsentation (**Engine B**) sowie einer persistenten/strukturell geteilten Struktur (**Engine C**) gegen Baseline **Engine A** über `10k` / `100k` Entities und Commit-Historien, inklusive der bestehenden `Stage1`–`Stage6`-Contract-Testsuites (`P-01`).

---

## Eintrag 07 — Stufe 7: Storage-Experimente (Engine A vs. B1/B2 CSR vs. C Persistent Radix-32 Trie)

- **Datum:** 2026-11-17
- **Stufe / Hypothese:**  
  **Stufe 7** (`P-01`–`P-04`, `H3`–`H4`, Workloads `W1`–`W7`):  
  1. Eine kompakte CSR-Repräsentation (`CompactGraphSnapshot`) mit legalem Payload-Interning (`ValueInterner`, Engine B1/B2) spart deutlich Heap gegenüber `HashMap`-Snapshots (`Engine A`).  
  2. Eine strukturell geteilte Radix-32-Baumstruktur (`PersistentLongMap` / `PersistentTreeVersionedDatabase`, Engine C) erreicht sowohl den geringsten Speicherzuwachs über viele Commits und `500` parallele Branches (`W4`) als auch konstante Sub-Mikrosekunden-`asOf`-Lesezeiten ohne Delta-Rekonstruktionsstrafe (`W1b`, `W7`).

- **Implementiert:**  
  - `veg-engine-memory`: Entkopplung über `CommitPublicationTarget`, `PropagationRuleSource` sowie `GraphReader`-basierte `PermissionEvaluator`, `SemanticInferenceEngine`, `AuthorizedGraphReaderImpl` und `ThreeWayMergeEngine`; **Engine B1/B2** (`ValueInterner`, `CompactGraphSnapshot`, `CompactVersionedDatabase`); **Engine C** (`PersistentLongMap`, `PersistentGraphSnapshot`, `PersistentTreeVersionedDatabase`).
  - `veg-engine-memory` Tests: `CompactEngineContractTest` (B1 Stage 1–6 + B2 Stage 3–6 + Checkpoint-Unit-Test = **158 Tests**) und `PersistentTreeEngineContractTest` (C Stage 1–6 + `PersistentLongMap` & Structural-Sharing-Tests = **90 Tests**).
  - `veg-benchmarks`: `StorageEngineComparisonRunner` (`W1a`, `W1b`, `W2`, `W3`, `W4`, `W5`, `W6`, `W7` über alle 4 Engines mit CSV/JSON-Export) + `StorageEngineComparisonRunnerTest`.
  - `veg-cli`: `runStage7Demo` und neuer Befehl `storage-experiment` in `VegCliMain` + Test in `VegCliMainTest`.
  - Dokumentation & Rohdaten: [docs/experiments/stage7-storage-comparison/](experiments/stage7-storage-comparison/analysis.md) (`README.md`, `results.csv`, `results.json`, `analysis.md`), [ADR-0008](adr/0008-storage-engine-comparison-and-persistent-tree-selection.md), [INVARIANTS.md](INVARIANTS.md) (`I-22`), [ARCHITECTURE.md](ARCHITECTURE.md), [CONCEPT.md](CONCEPT.md).

- **Tests (bestanden/fehlgeschlagen):**  
  `.\mvnw.cmd clean verify` — **377 / 377 Tests bestanden, 0 fehlgeschlagen, 0 übersprungen**:
  - `veg-model`: 19 / 19 bestanden
  - `veg-engine-memory`: **350 / 350** bestanden (Engine A Contract- & `jqwik`-Suites 94, `CompactEngineContractTest` B1+B2 166, `PersistentTreeEngineContractTest` C 90 — alle Stage-1–6-Verträge identisch erfüllt)
  - `veg-benchmarks`: 3 / 3 bestanden
  - `veg-cli`: 5 / 5 bestanden

- **Messungen und Reproduzierbarkeit:**  
  `java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar storage-experiment --entities 2000 --commits 50 --branches 500 --seed 42 --output docs/experiments/stage7-storage-comparison/results.csv --json docs/experiments/stage7-storage-comparison/results.json`:
  - **Retained Heap (Basisstand `3.107` Entities + `50` Commits):** `Engine A = 22.378.880 B` (`7.202,73 B/ent`) vs. `B1 = 9.634.560 B` (`3.100,92 B/ent`, **-56,9 %**) vs. `B2 = 1.490.768 B` (`479,81 B/ent`, **-93,3 %**) vs. **`Engine C = 1.180.512 B` (`379,95 B/ent`, -94,7 %)**.
  - **`W4` (`500` Branches, `50` mit Commit):** `Engine A = 20.507.720 B` vs. `B1 = 8.488.024 B` vs. `B2 = 737.712 B` vs. **`Engine C = 69.952 B`** (**293× weniger als Engine A**).
  - **`W2` Write-Durchsatz & `W7` `asOf` p50:** **`Engine C = 1.593,68 Commits/s` / `300 ns`** vs. `A = 1.454,27 c/s / 800 ns` vs. `B1 = 604,82 c/s / 800 ns` vs. `B2 = 1.021,86 c/s / 845.500 ns` (B2 bricht unter historischen Reads `W1b`/`W7` um 3 Größenordnungen ein).
  - `demo` Exit-Code 0 (`Same LUBM+inc query across EngineA/B/C rows=8/8/8`, `Engine B checkpoints stored=3 across 8 commits`, `Engine C unique persistent radix-32 trie nodes across 8 commits=3471`).

- **Was widerlegt / bestätigt wurde:**  
  - **Bestätigt:** Persistent Radix-32 Tries (**Engine C**) liefern gleichzeitig den geringsten Speicherbedarf, den höchsten Write-Durchsatz bei kleinen Commits und konstante Sub-Mikrosekunden-`asOf`-Reads — deutlich überlegen gegenüber Vollkopien (`A`) und reinem Journal-Replay (`B2`) bei historischer Last.
  - **Bestätigt (`H5`):** 5-Hop-Propagation + ACL (`W3`: `3,51–4,89 ms`) hängt vom Graph-Traversal ab, nicht von der Map-Implementierung — gezieltes Index-/Reachability-Caching wird in Stufe 10 relevant.

- **Risiken und technische Schulden:**  
  1. Engine C hält alle Trie-Knoten aktuell noch als Java-Objekte rein im Hauptspeicher (`In-Memory`).
  2. Dauerhafte Crash-feste Disk-Persistenz, WAL, Prüfsummen (`fsync`) und Recovery (`T17`) folgen in Stufe 8.

- **Entscheidung (weiter / anpassen / verwerfen):**  
  **GO für Stufe 8 mit Engine C (`PersistentTreeVersionedDatabase`) + Append-Only WAL/Checkpoints**; `CompactGraphSnapshot` optional als komprimiertes Kaltarchiv-Format (`P-07`) bewahren; Vollkopien (`Engine A`) für Persistenz verworfen.

- **Nächster kleinster sinnvoller Versuch (Stufe 8):**  
  Native Disk-Persistenz (`veg-engine-persistent` oder persistenter Journal-/Checkpoint-Speicher auf Basis von Engine C) mit binärem Append-Only WAL, Prüfsummen, Neustart-Rekonstruktion und Crash-Injection (`T17`, `P-06`, `P-08`).

---

## Eintrag 08 — Stufe 8: Persistentes WAL, GZIP-Checkpoints, Kaltarchivierung & Crash-Recovery (`T17`)

- **Datum:** 2026-11-17
- **Stufe / Hypothese:**  
  **Stufe 8** (`P-06`, `P-07`, `P-08`, `T17`):  
  1. Engine C (`PersistentTreeVersionedDatabase`) lässt sich über `PersistenceObserver`-Hooks und einen on-demand `coldSnapshotLoader` mit einem `CRC32C`-gesicherten Append-Only WAL (`wal.log`), atomar ersetzter `refs.meta` und GZIP-Checkpoints (`checkpoints/ckpt-*.bin.gz`) zu einer crash-festen Disk-Engine (`PersistentDiskVersionedDatabase`) ausbauen, die alle `Stage1`–`Stage6`-Vertragstests (`88` Tests) exakt besteht.  
  2. Kaltarchivierung (`archiveHistoryBefore`) kompaktiert `wal.log` und entlastet den Heap durch Eviktion alter Snapshots in komprimierte `archive/segment-*.wal.gz`-Segmente, ohne `asOf`, `diff` oder `replayAsOf` auf archivierte Commits zu beschädigen (`P-07`).

- **Implementiert:**  
  - `veg-engine-memory`: Erweiterung von `PersistentGraphSnapshot` (`emptySnapshot`, `fromMaterialized`, `applyDelta`) und `PersistentTreeVersionedDatabase` (`createRecovered`, `PersistenceObserver`, `coldSnapshotLoader`, `findHotSnapshot`, `evictHotSnapshot`, `branchReadRestrictionsSnapshot`, `currentNextEntityId`).
  - `veg-engine-persistent` (`io.veg.engine.persistent`): `DurabilityMode`, `CrashPoint`, `SimulatedCrashException`, `StorageCorruptionException`, `RecoveryReport`, `BinaryGraphCodec`, `WriteAheadLogStore`, `CheckpointAndArchiveStore`, `PersistentDiskVersionedDatabase` (inkl. `backupTo`/`restoreFrom` und LRU-Kaltcache).
  - Tests in `veg-engine-persistent`: `PersistentDiskContractTest` (`Stage1`–`Stage6` = **88 Tests**) sowie `Stage8PersistenceAndRecoveryTest` (**10 Tests** für `P-06`–`P-08` & `T17`).
  - `veg-cli`: `runStage8Demo` in `VegCliMain` + Assertion-Erweiterung in `VegCliMainTest`.
  - Dokumentation: [ADR-0009](adr/0009-persistent-wal-checkpoints-and-crash-recovery.md), [INVARIANTS.md](INVARIANTS.md) (`I-23`, `I-24`), [ARCHITECTURE.md](ARCHITECTURE.md), [CONCEPT.md](CONCEPT.md), [README.md](../README.md).

- **Tests (bestanden/fehlgeschlagen):**  
  `.\mvnw.cmd clean verify` — **475 / 475 Tests bestanden, 0 fehlgeschlagen, 0 übersprungen**:
  - `veg-model`: 19 / 19 bestanden
  - `veg-engine-memory`: 350 / 350 bestanden
  - `veg-engine-persistent`: **98 / 98** bestanden (`PersistentDiskContractTest` 88 + `Stage8PersistenceAndRecoveryTest` 10)
  - `veg-benchmarks`: 3 / 3 bestanden
  - `veg-cli`: 5 / 5 bestanden

- **Messungen und Reproduzierbarkeit:**  
  - `java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar demo`: Exit-Code 0 (`VEG Stage 8 ... T17 simulated crash mid-WAL -> MID_WAL_FRAME_WRITE`, `Recovered from checkpoint=c3, replayedWalCommits=2, truncatedTailFrames=1, coldArchiveSegments=1, headMatchesPreCrash=true`).
  - Geprüfte Fehlerszenarien (`Stage8PersistenceAndRecoveryTest`):
    - Neustart nach Multi-Branch-Merge, allen 6 `ValueType`-Varianten, Tags & Branch-ACLs inkl. kollisionsfreier `EntityId`-Fortsetzung (`p06AndP08_cleanRestartReconstructsMultiBranchEntitiesEdgesValuesTagsAndAcls`).
    - Checkpoints alle 10 Commits reduzieren `replayedWalCommits < 26` beim Neustart (`p06_periodicCheckpointsAccelerateRestartReplay`).
    - Deterministische `T17`-Crash-Injektion an `MID_WAL_FRAME_WRITE`, `CORRUPTED_WAL_TAIL_CRC`, `AFTER_WAL_BEFORE_REFS_UPDATE` und `MID_CHECKPOINT_WRITE` bereinigt beschädigte Tails/Temp-Dateien bzw. zieht unindizierte WAL-Commits sauber auf den Ziel-Branch nach.
    - Bit-Flips in inneren WAL-Frames, Checkpoint-Dateien, Kaltarchiv-Segmenten oder `refs.meta` werfen zuverlässig `StorageCorruptionException` (`P-08`).

- **Was widerlegt / bestätigt wurde:**  
  - **Bestätigt (`P-06`–`P-08`, `T17`):** Die Trennung zwischen unveränderlicher Radix-32-Snapshot-Struktur im Speicher und binärem Append-Only WAL + `CRC32C`-Checkpoints + komprimiertem Kaltarchiv auf Disk bewahrt exakt dieselbe Transaktions-, ACL-, Query- und RDF-Semantik wie Engine A/B/C bei voller Crash-Sicherheit.
  - **Wichtiges Detail beim Restart-Allokator:** `refs.meta` plus Scan des maximalen `EntityId`-Zählers aus rehydrierten Commits/Snapshots verhindert nach einem Neustart ID-Kollisionen ab `1000` (auch wenn vor dem Crash nach `AFTER_WAL_BEFORE_REFS_UPDATE` `refs.meta` zurücklag).

- **Risiken und technische Schulden:**  
  1. `PersistentDiskVersionedDatabase` hält die Commit-Metadaten-Liste (`commits`) und jüngere Hot-Radix-Tries weiterhin im JVM-Heap (historische Snapshots `< cutoffCommitId` werden bei `archiveHistoryBefore` in `archive/` + LRU-Cache ausgelagert); echtes Out-of-Core-Paging einzelner Radix-Trie-Knoten bei Multi-Millionen-Hot-Sets sowie Multi-Writer-MVCC bleiben für Stufe 10.
  2. Synchronisierung erfolgt aktuell über Objekt-Monitore (`synchronized`) auf Schreibpfaden; parallele Reader + MVCC folgen in Stufe 10.

- **Entscheidung (weiter / anpassen / verwerfen):**  
  **Weiter** zu Stufe 10 (Reifung zur Mehrbenutzer-/Produktions-Engine: MVCC/Parallelität, automatische Index-Wahl, Bitemporalität; optionaler Oracle-CDC-Adapter als P3).

- **Nächster kleinster sinnvoller Versuch (Stufe 10):**  
  Implementierung von MVCC-Snapshot-Isolation für konkurrierende Reader und parallele Transaktionen auf Basis der `PersistentTreeVersionedDatabase` / `PersistentDiskVersionedDatabase`-Struktur sowie gezielter Reachability-/Prädikat-Indizes zur Senkung der `W3` 5-Hop+ACL-Latenz.
