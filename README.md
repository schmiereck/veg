# Versioned Entity Graph (VEG)

> Ein streng typisiertes, selbstbeschreibendes und durchgängig versioniertes Entity-/Relations-Graphsystem in **Java 25 LTS** — mit nativer Storage- und Versionierungs-Engine als Forschungsziel.

## Leitmotiv & Forschungsfrage

> *„Können wir einen vollständig versionierten, streng typisierten und möglichst selbstbeschreibenden Entity-/Relationsgraphen physisch so kompakt und effizient speichern, dass die Flexibilität des Modells nicht durch einen unverhältnismäßigen Aufwand für Speicher, Abfragen, Historie, Berechtigungen und Branch-Merges erkauft wird?“*

VEG verfolgt einen strikt experimentellen Stufenplan (**Stufe 0 bis 10**, siehe [docs/CONCEPT.md](docs/CONCEPT.md)): Semantik zuerst beweisen → Änderungshistorie & Branches korrekt umsetzen → alternative Storage-Engines gegeneinander messen → eigene persistente Engine & Recovery absichern.

---

## Projektstruktur

| Modul | Verantwortung |
|---|---|
| [`veg-model`](veg-model) | Unveränderliche Kernprimitiven (`EntityId`, `Value`, `EntityNode`, `Edge`, `BootstrapIds`) |
| [`veg-engine-api`](veg-engine-api) | Storage-unabhängige Schnittstellen (`GraphReader`, `GraphSession`) & typisierte Exceptions (`P-01`) |
| [`veg-engine-memory`](veg-engine-memory) | In-Memory Engines **A** (`HashMap`), **B** (kompaktes CSR + Checkpoints) & **C** (Persistent Radix-32 Trie) |
| [`veg-engine-persistent`](veg-engine-persistent) | Disk-Persistenz auf Engine C: `CRC32C`-WAL, atomare Refs, GZIP-Checkpoints, Kaltarchiv & Crash-Recovery |
| [`veg-test-fixtures`](veg-test-fixtures) | Backend-agnostische Contract-Tests (`T01`–`T04`) und deterministischer Graph-Generator (`P-02`) |
| [`veg-benchmarks`](veg-benchmarks) | Reproduzierbarer Benchmark-Harness mit CSV-/JSON-Export & JVM-Telemetrie |
| [`veg-cli`](veg-cli) | Ausführbare CLI (`demo`, `benchmark`, `assertion-experiment`) |
| [`docs`](docs) | Konzept, Glossar, Invarianten, Architektur, ADRs, Laborbuch (`RESEARCH_LOG.md`) & Experimente |

---

## Voraussetzungen & Quickstart

- **Java 25 LTS** (z. B. Eclipse Temurin 25)
- Kein vorinstalliertes Maven erforderlich — der Maven Wrapper (`./mvnw` / `.\mvnw.cmd`) ist enthalten.

### Build & Tests ausführen

```powershell
# Windows (PowerShell)
.\mvnw.cmd clean verify

# Linux / macOS
./mvnw clean verify
```

### Stage-1-Demo & Baseline-Benchmark ausführen

```powershell
# Paketieren des ausführbaren CLI-JARs
.\mvnw.cmd -q package -DskipTests

# Stage-1..8-Demonstration (Graph-Schema, Replay, Merge, ACLs, LUBM/BSBM Queries, Engine A/B/C & Disk-Recovery T17)
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar demo

# Externe Turtle-/N-Triples-Datei importieren, abfragen und re-exportieren
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar import-rdf `
  --input pfad/zur/datei.ttl `
  --query "MATCH (p:Product) WHERE p.price <= 500.0 SELECT p.productName, p.price ORDER BY p.price ASC" `
  --export out.ttl

# Reproduzierbarer Baseline-Benchmark (10.000 Personen, Seed 42)
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar benchmark `
  --entities 10000 --seed 42 --output docs/experiments/stage0-baseline/results.csv

# Stage-2 Assertion-Modell-Vergleich (Option A vs. Option B)
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar assertion-experiment `
  --pairs 5000 --seed 42 --densities 1,2,5 `
  --output docs/experiments/stage2-assertions/results.csv `
  --json docs/experiments/stage2-assertions/results.json

# Stage-7 Storage-Engine-Vergleich (Engine A vs. B1/B2 CSR vs. Engine C Persistent Radix-32 Trie)
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar storage-experiment `
  --entities 2000 --commits 50 --branches 500 --seed 42 `
  --output docs/experiments/stage7-storage-comparison/results.csv `
  --json docs/experiments/stage7-storage-comparison/results.json
```

---

## Entwicklungs-Roadmap (Überblick)

- [x] **Stufe 0:** Repository, Maven-Multi-Modul-Setup (Java 25 LTS), CI-Pipeline, Test-Oracle & Baseline-Benchmark-Harness
- [x] **Stufe 1:** Minimalgraph (`EntityId`, `Value`, `Edge`), Bootstrap-Kern im Graph, strenge Typprüfung, `T01`–`T04` & `ADR-0001`/`0002`
- [x] **Stufe 2:** Identität vs. Wertgleichheit, Shared-Value-Semantik (`IMMUTABLE`/`SHARED_MUTABLE`), Kardinalitäten & `Connection + Assertions` vs. `Edge = Assertion` (`ADR-0003`)
- [x] **Stufe 3:** Atomare Transaktionen, Savepoints, unveränderlicher Commit-DAG, `asOf`, `diff`, `revert` & Replay (`T05`–`T09`, `T15`, `ADR-0004`)
- [x] **Stufe 4:** Branches mit struktureller Teilung (`T16`), selektives Staging (`V-06`) & struktureller 3-Wege-Merge (`T10`–`T12`, `V-13`, `ADR-0005`)
- [x] **Stufe 5:** Versionsbewusste Berechtigungen ("Verbieten gewinnt", Entity/Relation/Attribut, `S-01`–`S-03`, `S-05`, `T13`–`T14`) & abgeleitete Aussagen (`M-12`, `ADR-0006`)
- [x] **Stufe 6:** Storage-unabhängige Query-API/DSL (`S-04`), RDF/Turtle-Import & -Export (`T18`, `S-06`, `ADR-0007`) sowie LUBM/BSBM-Datensätze
- [x] **Stufe 7:** Storage-Experimente & Benchmarks (**Engine A** Map vs. **Engine B1/B2** CSR/Checkpoints vs. **Engine C** Persistent Radix-32 Trie, `W1`–`W7`, `ADR-0008`)
- [x] **Stufe 8:** Native Disk-Persistenz, WAL/Commit-Log, Checkpoints, Kaltarchiv, Backup/Restore & Crash-Recovery (`P-06`–`P-08`, `T17`, `ADR-0009`)
- [ ] **Stufe 10:** Reifung zur Mehrbenutzer-Datenbank (MVCC, Auto-Indizes, Bitemporalität)
- [ ] **Stufe 9 *(Option / P3)*:** Optionaler Enterprise-/Oracle-CDC-/Outbox-Adapter

---

## Dokumentation & Laborbuch

- [Aktives R&D-Konzept](docs/CONCEPT.md) (basierend auf [doc/Versioned_Entity_Graph_RnD_Konzept.md](doc/Versioned_Entity_Graph_RnD_Konzept.md))
- [Glossar](docs/GLOSSARY.md) & [Systeminvarianten](docs/INVARIANTS.md)
- [Architektur & Austauschbarkeit für Stufe 3/4](docs/ARCHITECTURE.md)
- [ADR-0001: Semantische Aussagen auf Relationen](docs/adr/0001-semantic-assertions-on-relations.md)
- [ADR-0002: Grenzen des Bootstrap-Kerns](docs/adr/0002-bootstrap-kernel-boundaries.md)
- [ADR-0003: Shared-Value-Mutabilität, Uniqueness und Kardinalitäten](docs/adr/0003-shared-value-mutation-and-cardinality.md)
- [ADR-0004: Transaktions-Workspace, Commit-Envelope, Savepoints & Revert](docs/adr/0004-transaction-workspace-and-commit-log.md)
- [ADR-0005: Branching, selektives Staging & struktureller Drei-Wege-Merge](docs/adr/0005-branching-selective-staging-and-three-way-merge.md)
- [ADR-0006: Graph-Basierte Zugriffskontrolle ("Verbieten gewinnt") & Semantische Prädikat-Inferenz](docs/adr/0006-access-control-deny-wins-and-semantic-inference.md)
- [ADR-0007: Storage-unabhängige Query-API/DSL & RDF/Turtle-Interoperabilität](docs/adr/0007-query-dsl-and-rdf-turtle-interoperability.md)
- [ADR-0008: Storage-Engine-Vergleich & Wahl von Engine C (Persistent Radix-32 Trie)](docs/adr/0008-storage-engine-comparison-and-persistent-tree-selection.md)
- [ADR-0009: Persistentes WAL, komprimierte Checkpoints, Kaltarchivierung & Crash-Recovery](docs/adr/0009-persistent-wal-checkpoints-and-crash-recovery.md)
- [Stage-7 Storage-Experiment-Auswertung](docs/experiments/stage7-storage-comparison/analysis.md)
- [Forschungslog](docs/RESEARCH_LOG.md)

## Lizenz

Dieses Projekt steht unter der [Apache License 2.0](LICENSE).
