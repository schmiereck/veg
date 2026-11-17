# Versioned Entity Graph (VEG) — Aktives R&D-Konzept

> **Basisdokument:** [../doc/Versioned_Entity_Graph_RnD_Konzept.md](../doc/Versioned_Entity_Graph_RnD_Konzept.md) (Stand 17. November 2026)  
> **Aktueller Status:** Stufen 0 bis 4 umgesetzt; Fokus auf eigenständiger nativer Engine in **Java 25 LTS**.

## Verbindliche Präzisierungen gegenüber dem Ursprungskonzept

1. **Programmiersprache & Laufzeitumgebung:**  
   Festgelegt auf **Java 25 LTS** (Temurin 25). Moderne Sprachkonstrukte (`record`, `sealed interface`, Pattern Matching) prägen das Domänenmodell; ab Stufe 7 steht die *Foreign Function & Memory API* für kompakte Off-Heap-Repräsentationen zur Verfügung.
2. **Strategische Priorisierung — Eigene Basis statt Oracle-Abhängigkeit:**  
   Während das ursprüngliche Konzept aus dem Unternehmensumfeld eine mögliche Oracle-Integration (`P-09`, Hypothese `H7`, Stufe 9) als zentralen Adoptionspfad führte, entwickeln wir VEG vorrangig als **eigenständiges Persistenz- und Versionierungssystem** mit nativer Storage-Engine (Stufe 7 & Stufe 8). Der Oracle-/Outbox-/CDC-Adapter bleibt als **niedrig priorisierte Option (P3)** dokumentiert und schränkt keine Kernentscheidungen ein.
3. **Open-Source-Repository-Standard:**  
   Multi-Modul-Maven-Projekt unter **Apache License 2.0** mit reproduzierbarem Build (`mvnw`), automatischer CI-Pipeline und Backend-agnostischer Contract-Test-Suite (`veg-test-fixtures`).

---

## Gesamt-Roadmap (angepasst)

| Phase | Stufen | Ziel |
|---|---|---|
| **Phase I: Semantischer Kern** | Stufe 0, 1, 2 | Minimalgraph, Bootstrap-Selbstbeschreibung, strenge Typprüfung, Identität vs. Sharing, Assertions |
| **Phase II: Versionierung & Merge** | Stufe 3, 4 | Atomare Transaktionen, Savepoints, Commit-DAG, `asOf`, `diff`, `revert`, Branches, selektives Staging, 3-Wege-Merge |
| **Phase III: Sicherheit & Abfragen** | Stufe 5, 6 | Feld-/Relations-ACLs mit Zyklusschutz, Query-API/DSL, RDF-Export |
| **Phase IV: Eigene Storage-Engine** | Stufe 7, 8 | Engine A (Map) vs. B (Compact) vs. C (Persistent Tree), WAL, Snapshots, Crash-Recovery |
| **Phase V: Reifung & Optionale Adapter** | Stufe 10, *(Stufe 9 / P3)* | MVCC/Konkurrenz, automatische Indizes, Bitemporalität; optionaler Oracle-CDC-Adapter als P3 |

Für die vollständigen Anforderungen (`M-01`–`M-14`, `V-01`–`V-17`, `S-01`–`S-09`, `P-01`–`P-10`), Hypothesen (`H1`–`H7`), Pflichtszenarien (`T01`–`T18`) und Benchmark-Workloads (`W1`–`W7`) gilt weiterhin [../doc/Versioned_Entity_Graph_RnD_Konzept.md](../doc/Versioned_Entity_Graph_RnD_Konzept.md).

### Aktueller Implementierungsstand (nach Stufe 7)

- **Stufen 0–4:** Bootstrap-Kern, Shared-Value-Semantik, Savepoints/Commit-DAG/Replay, Branches, selektives Staging und konfliktsicherer 3-Wege-Merge.
- **Stufe 5:** Graph-gespeicherte ACLs mit **Default Deny** und **"Verbieten gewinnt"**, zyklussichere deklarative Rechtevererbung (`OUTGOING`/`INCOMING`/`BOTH`), Branch-Leserestriktionen (`S-05`), zeitreisensichere `authorizedAsOf`-Sichten (`T14`), Diagnosepfade (`AccessDecision`, `S-03`) sowie Query-Time-Inferenz (`HAS_INVERSE_PREDICATE`, `HAS_SUPER_PREDICATE`, `M-12`). Siehe [ADR-0006](adr/0006-access-control-deny-wins-and-semantic-inference.md).
- **Stufe 6:** Storage-unabhängige Fluent-Query-API (`GraphQuery`) & deklarative Query-DSL (`GraphQueryDsl`), Turtle/N-Triples-Import (`RdfGraphImporter`) und -Export mit `rdf:Statement`-Provenienz & Warnungen (`RdfGraphExporter`, `T18`) sowie LUBM- und BSBM-Benchmark-Datensätze. Siehe [ADR-0007](adr/0007-query-dsl-and-rdf-turtle-interoperability.md).
- **Stufe 7:** Vollständige Äquivalenzprüfung und `W1`–`W7`-Benchmark-Vergleich zwischen **Engine A** (`HashMap`), **Engine B1/B2** (kompaktes CSR + Checkpoints + `ValueInterner`) und **Engine C** (`PersistentLongMap` Radix-32-Trie). Go-Entscheidung für **Engine C** als Grundlage der Disk-Persistenz in Stufe 8. Siehe [Auswertung](experiments/stage7-storage-comparison/analysis.md) & [ADR-0008](adr/0008-storage-engine-comparison-and-persistent-tree-selection.md).
