# ADR-0007: Storage-unabhängige Query-API/DSL & RDF/Turtle-Interoperabilität (`T18`)

- **Status:** Akzeptiert (Stufe 6)
- **Datum:** 2026-11-17

## Kontext

Für Stufe 6 (`S-04`, `S-06`, `S-07`, `P-01`, `M-12`, `T18`) muss VEG Abfragen, Traversierungen, Sortierung, Paging und RDF-Austausch vollständig entkoppelt vom physischen Storage-Backend unterstützen. Sowohl reguläre `GraphReader`-Snapshots (`main`, Feature-Branch, `asOf`) als auch `AuthorizedGraphReader`-Sichten müssen dieselbe Abfrageschicht nutzen können, ohne Rechte- oder Provenienzregeln zu umgehen.

## Entscheidung

1. **Abfrageschicht strikt auf `GraphReader` in `veg-engine-api`:**
   - `GraphQuery` (Fluent API) und `GraphQueryDsl` (`[USING DERIVED] MATCH ... JOIN ... WHERE ... SELECT ... ORDER BY ... LIMIT ... OFFSET ...`) arbeiten ausschließlich über Methoden des Interfaces `GraphReader`.
   - Da `AuthorizedGraphReader` selbst `GraphReader` implementiert und alle `findEntity`-, `findOutgoingEdges`-, `allEntities`- und `findAllOutgoingAssertions`-Aufrufe filtert, werden Attribut-, Relations-, Zeitreise- und Branch-Restriktionen (`S-01`, `S-05`, `T14`) automatisch und orakelfrei in `WHERE`, `JOIN` und `SELECT` durchgesetzt.
2. **Deterministische Auswertung:**
   - Zeilen ohne sortiertes Attribut landen immer am Ende (`nulls last`), Gleichstände werden deterministisch nach kanonischer Alias- und `EntityId`-Ordnung gebrochen.
   - `USING DERIVED` schaltet zwischen expliziten Kanten (`findOutgoingEdges`) und abgeleiteten + expliziten Aussagen (`findAllOutgoingAssertions`, `M-12`) um.
3. **Verlustbewusster / nachvollziehbarer RDF-Export (`RdfGraphExporter`, `T18`) & Turtle/N-Triples-Import (`RdfGraphImporter`):**
   - Kanten-zu-Kanten-Provenienz (`hasWeight`, `hasConfidence`, `supportedBy`, `S-07`) wird über Standard-Reifikation (`rdf:Statement`) in Turtle (`.ttl`) und N-Triples (`.nt`) exportiert und beim Re-Import direkt als Edge-zu-Edge-Kanten an der jeweiligen VEG-`EdgeId` rekonstruiert.
   - Ist `includeReifiedProvenance=false`, werden betroffene Kanten **niemals stillschweigend** ohne Hinweis gekürzt, sondern explizit in `RdfExportResult.warnings()` gemeldet (`T18`).
   - Abgeleitete Aussagen werden bei `includeDerivedAssertions=true` über `veg:assertionOrigin` markiert, sodass der Importer explizite vs. abgeleitete Aussagen (`M-12`) sauber getrennt hält.
   - Für importierte RDF-Literale nutzt `RdfGraphImporter` geteilte skalar-typisierte `rdf.literal.<ValueType>`-Werttypen, damit Klassennamen und Attributnamen in VEG schemakollisionsfrei bleiben.

## Konsequenzen

- **Vorteile:**
  - Künftige Storage-Engines (Stufe 7: Engine B mit Compact Maps/FFM, Engine C persistenter B-Tree) erben die komplette Query-DSL sowie Turtle-/N-Triples-Import/Export ohne zusätzlichen Adaptercode.
  - Realistische Standard-Benchmarks (**LUBM** und **BSBM**) laufen direkt als Reproducible-Tests auf VEG.
- **Nachteile / Bewusste Grenzen:**
  - `GraphQuery` evaluiert `MATCH` und `JOIN` aktuell per In-Memory-Pipeline auf `GraphReader`; ein kostenbasierter Query-Optimizer sowie Indexpflege für sehr große Bestände folgen nach den Stufe-7-Storage-Messungen bzw. in Stufe 10.
  - `GraphQueryDsl` deckt gezielt den vereinbarten MVP-Kern (`USING DERIVED`, `MATCH`, `JOIN`, `WHERE ... AND ...`, `SELECT`, `ORDER BY`, `LIMIT`, `OFFSET`) ab (`OR`, `GROUP BY`, vollständiges SPARQL bleiben Stufe 10/Erweiterungen vorbehalten).
