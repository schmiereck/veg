# Versioned Entity Graph (VEG)

> Ein streng typisiertes, selbstbeschreibendes und durchgängig versioniertes Entity-/Relations-Graphsystem in **Java 25 LTS** — mit nativer Storage- und Versionierungs-Engine als Forschungsziel.

## Leitmotiv & Forschungsfrage

> *„Können wir einen vollständig versionierten, streng typisierten und möglichst selbstbeschreibenden Entity-/Relationsgraphen physisch so kompakt und effizient speichern, dass die Flexibilität des Modells nicht durch einen unverhältnismäßigen Aufwand für Speicher, Abfragen, Historie, Berechtigungen und Branch-Merges erkauft wird?“*

VEG verfolgt einen strikt experimentellen Stufenplan (**Stufe 0 bis 10**, siehe [docs/CONCEPT.md](docs/CONCEPT.md)): Semantik zuerst beweisen → Änderungshistorie & Branches korrekt umsetzen → alternative Storage-Engines gegeneinander messen → eigene persistente Engine & Recovery absichern.

---

## Projektstruktur

| Modul | Verantwortung |
|---|---|
| [`veg-model`](veg-model) | Unveränderliche Kernprimitiven (`EntityId` mit 64-Bit-Seq. & 128-Bit-UUIDv7/v4, `IdGenerationStrategy`, `Value`, `EntityNode`, `Edge`, `BootstrapIds`) |
| [`veg-engine-api`](veg-engine-api) | Storage-unabhängige Schnittstellen (`GraphReader`, `GraphSession`, `VersionedGraphDatabase`), `GraphQueryDsl`, RDF & `VegPatchService` (`.vegpatch`) |
| [`veg-engine-memory`](veg-engine-memory) | In-Memory Engines **A** (`HashMap`), **B** (kompaktes CSR + Checkpoints) & **C** (Persistent Radix-32 Trie / `PersistentEntityMap`), 3-Wege-Merge & ACLs |
| [`veg-engine-persistent`](veg-engine-persistent) | Disk-Persistenz auf Engine C: `CRC32C`-WAL, atomare Refs, GZIP-Checkpoints, Kaltarchiv & Crash-Recovery |
| [`veg-test-fixtures`](veg-test-fixtures) | Backend-agnostische Contract-Tests (`T01`–`T18`) und deterministischer Graph-Generator (`P-02`) |
| [`veg-benchmarks`](veg-benchmarks) | Reproduzierbarer Benchmark-Harness mit CSV-/JSON-Export & JVM-Telemetrie |
| [`veg-cli`](veg-cli) | Ausführbare CLI (`demo`, `import-rdf`, `import-project-docs`, `db`, `mcp`, `studio`, `desktop`, Benchmarks), **VEG Access Studio** & `VEG-Studio.exe` (`jpackage`) |
| [`data/project-kb`](data/project-kb) & [`.agents`](.agents) | Persistenter Projekt-Wissensgraph (`I-01`–`I-28`, `ADR-0001`–`0010`, Stufen, Module) sowie MCP-Plugin & Agent-Skill |
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

# Stage-1..10-Demonstration (Schema, Replay, Merge, ACLs, LUBM/BSBM Queries, Engine A/B/C, Disk-Recovery T17 & MVCC/Bitemporal/GDPR)
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

### Coding-Agent-Zugriff, Wissensgraph & VEG Access Studio

Die Projektdokumentation (`README.md`, `docs/*.md`, `docs/adr/*.md`) lässt sich direkt in eine persistente VEG-Datenbank überführen und über **CLI**, **MCP (Stdio JSON-RPC 2.0)** oder **VEG Access Studio** erkunden, abfragen, bearbeiten und über Text-Patches (`.vegpatch`) Git-artig zusammenführen:

```powershell
# 1. Projekt-Markdowns als versionierten Wissensgraph nach data/project-kb überführen
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar import-project-docs --db data/project-kb --docs-root . --clean

# 2. Direktabfragen & Mutationen per CLI (inkl. UUIDv7-Vergabe und .vegpatch-Workflow)
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb schema
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb datasheet --type Invariant --search WAL
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb query --dsl 'MATCH (a:ArchitectureDecision) JOIN (a)-[addressesInvariant]->(i:Invariant) WHERE i.id = "I-17" SELECT a.id, a.title, i.id'
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb create-object --id-strategy uuid-v7 --type GlossaryTerm --attr id=UUID --attr title="128-Bit EntityId" --actor admin --reason "UUID-Beispiel"
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb export-patch --from c4 --to c5 --output pr.vegpatch
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb apply-patch --input pr.vegpatch --mode merge

# 3. MCP-Stdio-Server für Coding-Agents (verbindet sich automatisch mit einem laufenden VEG-Studio-Server, falls aktiv)
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar mcp --db data/project-kb
# Optional explizit gegen einen laufenden Server oder strikt lokal:
# java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --server http://127.0.0.1:8080 schema
# java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar mcp --db data/project-kb --local

# 4. VEG Access Studio (grafische Browser-Oberfläche unter http://127.0.0.1:8080/studio/index.html)
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar studio --db data/project-kb --port 8080

# 5. Windows Desktop App (lokaler Server-Manager + automatischer Start von VEG Access Studio)
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar desktop --db data/project-kb --port 8080

# Natives Windows-Paket (VEG-Studio.exe inkl. eingebetteter JRE & project-kb) per JDK-25 jpackage bauen:
.\mvnw.cmd package -Pwindows-exe -DskipTests
.\veg-cli\target\dist\VEG-Studio\VEG-Studio.exe
```

---

## Neue Features im Detail

### 1. Verteilte Identitäten: 128-Bit-UUIDs & `IdGenerationStrategy`

Um verteilte Datensätze offline und kollisionsfrei über mehrere Instanzen hinweg anzulegen, unterstützt [EntityId](veg-model/src/main/java/io/veg/model/EntityId.java) sowohl kompakte 64-Bit-IDs (`msb == 0`, Bootstrap `1..999`, sequenziell ab `1000`) als auch vollwertige **128-Bit-UUIDs**:

- **Austauschbare Strategien ([IdGenerationStrategy](veg-model/src/main/java/io/veg/model/IdGenerationStrategy.java)):**
  - `SEQUENTIAL` *(Standard)*: streng monotone Vergabe ab `1000`.
  - `UUID_V7`: zeitgeordnete RFC-9562-UUIDv7 für gute Präfix-Lokalität im Radix-Trie.
  - `UUID_V4`: zufallsverteilte RFC-4122-UUIDv4.
- **Engine-übergreifende Unterstützung:** Funktioniert identisch in Engine A (`InMemoryVersionedDatabase`), Engine B (`CompactVersionedDatabase`), Engine C (`PersistentTreeVersionedDatabase` über das zweistufige `PersistentEntityMap`) sowie in der Disk-Persistenz (`PersistentDiskVersionedDatabase` / `BinaryGraphCodec`, byte-kompatibel zu bestehenden sequenziellen WALs).
- **Umschaltbar zur Laufzeit:** `db.setIdGenerationStrategy(IdGenerationStrategy.UUID_V7)` bzw. per CLI `--id-strategy uuid-v7`.

### 2. Git-fähiges Text-Diff-/Patch-Format (`.vegpatch`) & 3-Wege-Merge

Mit [VegPatchService](veg-engine-api/src/main/java/io/veg/engine/VegPatchService.java) lassen sich Commit-Diffs in ein deterministisches, zeilenbasiertes Textformat (`VEG-PATCH v1`) exportieren, in Git einchecken, per `git diff` reviewen und später wieder in bestehende Datenbanken einspielen oder per **3-Wege-Merge** zusammenführen:

```text
VEG-PATCH v1
patch-id: sha256:...
base-commit: c5
target-commit: c6
source-branch: contrib/bob
actor: bob
category: REFERENCE
reason: Add bio predicate and UUID entity

# --- SCHEMA ---
+VALUE_TYPE #01933b4a-... name="Bio" scalar=STRING unique=false
+PREDICATE  #01933b4a-... name="hasBio" domain=@Type:Person range=@Type:Bio maxOut=1 maxIn=* uniqueTriple=true

# --- ENTITIES ---
+OBJECT #01933b4a-... type=@Type:Person
+VALUE  #01933b4a-... type=@Type:Bio mutability=IMMUTABLE value=STRING:"Graph Engineer"

# --- EDGES ---
+EDGE #01933b4a-... (#01933b4a-...) -[@Pred:hasBio]-> (#01933b4a-...)
```

- **Symbolische Schema-Auflösung (`@Type:<Name>`, `@Pred:<Name>`):** Schema-Typen und Prädikate werden logisch über ihren Namen aufgelöst — auch zwischen Datenbanken mit unterschiedlicher Entstehungshistorie.
- **Zwei Übernahme-Modi (`PatchApplyMode`):**
  - `APPLY`: atomare Transaktion direkt auf dem aktuellen `HEAD` des Zielbranches.
  - `MERGE`: legt automatisch einen Incoming-Branch `patch/<shortHash>` ab `base-commit` an und führt die strukturelle `ThreeWayMergeEngine` aus. Konkurrierende Änderungen an funktionalen Attributen (`CONCURRENT_FUNCTIONAL_ATTRIBUTE`) oder Schema-/Kardinalitätskonflikte werden sauber gemeldet, ohne den Zielbranch zu verändern.
- **Idempotenz:** Bereits eingespielte Patches werden anhand ihrer `patch-id` (`correlationId` bzw. Commit-Abstammung) erkannt und nicht doppelt angewendet.

### 3. Coding-Agent-Zugriff (MCP-Server, Skill & Projekt-Wissensgraph)

Coding-Agents greifen über **MCP (JSON-RPC 2.0 über Stdio)** oder **`veg-cli db`** direkt auf Schema, Daten und Historie zu:

- **Konfiguration im Repository:** [`.agents/plugins/veg-mcp/mcp_config.json`](.agents/plugins/veg-mcp/mcp_config.json), Skill [`.agents/skills/veg-database/SKILL.md`](.agents/skills/veg-database/SKILL.md) und Projektregel [`AGENTS.md`](AGENTS.md).
- **MCP-Werkzeuge (`veg-cli mcp --db <dir>`):**

| Tool | Aufgabe |
|---|---|
| `veg_schema` | Komplettes Schema (Objekt-/Werttypen mit Zeilenzahlen, Prädikate, Branches, Tags) lesen |
| `veg_datasheet` | Tabellarische Sicht eines Typs mit Volltext-/Spaltenfiltern, Sortierung und Paging |
| `veg_query` | Mehrstufige `GraphQueryDsl`-Abfragen (auch historisch via `asOf` oder bitemporal `validAt`) |
| `veg_entity` | Detailansicht einer Entity (Attribute, ein-/ausgehende Relationen, Historie) |
| `veg_history` | Commit-DAG-Übersicht mit Akteuren, Zeitstempeln und Begründungen |
| `veg_mutate` | Atomare Schema-/Daten-Commits (`create_object` inkl. `idStrategy`, `set_attribute`, `connect`, `disconnect`, `define_*`) |
| `veg_patch` | Exportieren (`export`), direktes Einspielen (`apply`) oder 3-Wege-Merge (`merge`) von `.vegpatch`-Diffs |

- **Projekt-Wissensgraph (`data/project-kb`):** Der Importer (`import-project-docs`) überführt `README.md`, `docs/*.md` und `docs/adr/*.md` über strukturierte Commits (`c1`–`c5`, Tag `v1.0-stage10`) in die Objekttypen `Document`, `Module`, `Stage`, `GlossaryTerm`, `Invariant`, `ArchitectureDecision` und `ResearchEntry`.

### 4. VEG Access Studio (Grafische Browser-Oberfläche)

**VEG Access Studio** (`veg-cli studio --db data/project-kb --port 8080`, erreichbar unter `http://127.0.0.1:8080/studio/index.html`) bietet eine an MS Access angelehnte Oberfläche ohne externe Web-Abhängigkeiten:

- **Objektkatalog (links):** Alle Objekttypen, Werttypen und Prädikate mit Live-Zählern.
- **Datenblatt & Unterdatenblatt:** Dynamische Tabellenspalten je Objekttyp, Spalten- und Volltextfilter, Sortierung, Inline-Zellbearbeitung per Doppelklick, `+ Neuer Datensatz`-Dialog sowie rechts das Unterdatenblatt mit klickbarer Relationen-Navigation (`+ Relation verknüpfen` / `Trennen`) und Entity-Historie.
- **Beziehungen & Schema:** Kartenansicht aller Typen und Relationen samt Formularen zum Anlegen neuer `ObjectType`s, `ValueType`s und `Predicate`s.
- **Abfrage-Assistent:** Dropdown-Builder zur Erzeugung von `GraphQueryDsl`-Abfragen inkl. Presets und klickbarer Ergebnistabelle.
- **Historie, globale `asOf`-Zeitreise & `.vegpatch`-Bridge:** Commit-Liste mit Ein-Klick-Zeitreise, Commit-Diff-Ansicht sowie Export und 3-Wege-Merge von `.vegpatch`-Patches direkt im Browser.

### 5. Windows Desktop App (`VEG-Studio.exe` – Server-Manager & App-Launcher)

Für einen niedrigen Einstieg ohne Kommandozeile bündelt das Maven-Profil `-Pwindows-exe` eine eigenständige Windows-Desktop-Anwendung (**Variante A**: nativer Server-Manager + Browser/App-Fenster für **VEG Access Studio**):

- **Server-Manager & System Tray ([VegDesktopApp](veg-cli/src/main/java/io/veg/cli/desktop/VegDesktopApp.java) / [VegServerController](veg-cli/src/main/java/io/veg/cli/desktop/VegServerController.java)):** Natives Windows-Swing-Fenster mit System-Tray-Icon (`pic/logo-veg-smal.jpg`), Live-Statuskarte (aktives DB-Verzeichnis, Studio-URL, Branch/`HEAD`, Entity-/Edge-/Commit-Zähler, `IdGenerationStrategy`), Live-Transaktions-/Server-Log und automatischem Öffnen von **VEG Access Studio** als Edge-/Chrome-App-Fenster (`--app=...`) bzw. im Standard-Browser.
- **Gemeinsamer Zugriff für Browser, CLI & Coding-Agents (MCP):** Läuft `VEG-Studio.exe` (oder `veg-cli studio`), leiten `veg-cli db` und `veg-cli mcp` Abfragen und Mutationen automatisch per HTTP (`/api/server-info`, `/api/mcp`, `/api/*`, entdeckt über `<dbDir>/server.active` bzw. Default-Port `8080`, steuerbar über `--server <url>` oder `--local`) an den laufenden Server weiter — Änderungen erscheinen sofort im Server-Manager-Log und in **VEG Access Studio**.
- **Menüleiste zur Server- & DB-Verwaltung:**
  - **Datenbank:** `DB-Verzeichnis öffnen / wechseln...`, `Neue leere Datenbank anlegen...`, `Checkpoint jetzt schreiben`, `Kaltarchivierung bis Commit...`, `Backup erstellen...`, `Backup wiederherstellen...`, `Beenden`
  - **Server:** `Server starten`, `Server stoppen`, `Server neu starten`, `Port ändern...`, `ID-Vergabestrategie` (`SEQUENTIAL`, `UUID_V7`, `UUID_V4`)
  - **Import / Export & Git-Patch:** `Projekt-Dokumentation (.md) in DB importieren...`, `RDF-Datei (.ttl / .nt) importieren...`, `Aktuellen Graph als RDF (.ttl / .nt) exportieren...`, `Git-Patch (.vegpatch) 3-Wege-Merge...`, `Git-Patch (.vegpatch) direkt auf HEAD anwenden...`, `Commit-Diff als .vegpatch exportieren...`
  - **Browser & Ansicht:** `VEG Access Studio im App-Fenster öffnen`, `VEG Access Studio im Standard-Browser öffnen`, `Studio-URL kopieren`, `Status aktualisieren`
- **Maven-Build für die `.exe` (erfordert JDK 25 `jpackage` / `jlink` unter Windows):**
  - Build: `.\mvnw.cmd package -Pwindows-exe -DskipTests` *(oder vollständige Verifikation inkl. Tests via `.\mvnw.cmd clean verify -Pwindows-exe`)*
  - Ausgabe: Portables App-Image unter `veg-cli/target/dist/VEG-Studio/VEG-Studio.exe` inkl. privater Runtime und mitgelieferter Beispiel-Datenbank `data/project-kb`.

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
- [x] **Stufe 10:** Reifung zur Mehrbenutzer-Datenbank (MVCC, Idempotenz, Auto-Indizes, Bitemporalität, Cherry-Pick & DSGVO-Redaktion, `ADR-0010`)
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
- [ADR-0010: Multi-User MVCC, automatische Typ-Indizes, Bitemporalität, Cherry-Pick & DSGVO-Redaktion](docs/adr/0010-mvcc-auto-indexing-bitemporality-and-gdpr-redaction.md)
- [Stage-7 Storage-Experiment-Auswertung](docs/experiments/stage7-storage-comparison/analysis.md)
- [Forschungslog](docs/RESEARCH_LOG.md)

## Lizenz

Dieses Projekt steht unter der [Apache License 2.0](LICENSE).
