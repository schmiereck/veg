# ADR-0005: Branching, selektives Staging & struktureller Drei-Wege-Merge

- **Status:** Akzeptiert (Stufe 4)
- **Datum:** 2026-11-17
- **Kontext:** Hypothesen `H4` & `H5`, Anforderungen `V-05`–`V-08`, `V-12`, `V-13`, `P-04`, `T10`–`T12`, `T16`

## Problemstellung

Beim Übergang von einer linearen Historie (Stufe 3) zu einem branchfähigen Commit-DAG mit selektivem Staging und Drei-Wege-Merge sind vier Designfragen zu lösen:
1. **Branch-übergreifende ID-Kollisionen:** Wie verhindern wir, dass parallele Branches dieselbe `EntityId` vergeben?
2. **Branch-Effizienz (`T16` / `P-04`):** Wie bleibt das Anlegen vieler Branches frei von Vollkopien?
3. **Selektives Staging (`V-06`):** Wie lassen sich unabhängige Änderungen aus einem Arbeitsbereich einzeln committen, ohne Schema- oder Relationsabhängigkeiten zu brechen?
4. **Drei-Wege-Merge & Betriebsdatenschutz (`V-07`, `V-13`, `H5`):** Wie werden konfliktfreie Daten-/Schemaänderungen automatisch gemergt, Konflikte explizit erkannt und produktive Betriebsdaten vor versehentlichem Feature-Merge geschützt?

## Entscheidung

### 1. Gemeinsamer `EntityId`-Allokator über alle Branches & strukturelles Branch-Sharing
- Alle Workspaces, Transaktionen und Branches derselben Datenbank teilen einen atomaren `AtomicLong`-Allokator (`sharedUserEntityIdAllocator`). Dadurch erzeugen parallele Branches niemals identische `EntityId`s für unterschiedliche Objekte.
- Ein `Branch` ist lediglich ein benannter Zeiger `(name, headCommitId)` auf einen unveränderlichen Snapshot. Das Anlegen von 500 Branches auf einem gefüllten Graphen erzeugt null Entitäts- oder Kantenkopien (`T16`).

### 2. Gruppiertes selektives Staging mit atomarer Abhängigkeitsprüfung (`InMemoryStagingWorkspace`)
- Operationen im `StagingWorkspace` werden ihrem logischen Ziel (`EntityId` des Typs, Prädikats, Objekts, Werts oder der Edge) zugeordnet — Hilfsänderungen wie `HAS_NAME` oder `HAS_MUTABILITY` wandern automatisch im Paket ihrer logischen Entity.
- Vor jedem `commitStaged(...)` prüft `applyAndValidateStaged`, ob alle benötigten Typen, Endpunkte, Prädikate und abhängigen Kanten entweder bereits im Ziel-Branch oder im gestagten Satz vorhanden sind (`MissingDependencyException`). Unstaged-Änderungen bleiben erhalten.

### 3. `DataCategory` & `MergePolicy` gegen versehentlichen Betriebsdaten-Merge (`V-13`)
- Jeder Commit trägt eine `DataCategory` (`SCHEMA`, `REFERENCE`, `TEST`, `OPERATIONAL`).
- `MergePolicy.developmentDefault()` übernimmt ausschließlich `SCHEMA` und `REFERENCE` und schließt `OPERATIONAL` sowie `TEST` automatisch aus. `MergePolicy.includeAll()` erlaubt bei Bedarf einen expliziten Voll-Merge.

### 4. Struktureller Drei-Wege-Merge mit sechs expliziten Konfliktklassen (`ThreeWayMergeEngine`)
- Gemeinsamer Vorfahre (`findCommonAncestor`) über BFS auf dem Commit-DAG (`Commit.parentIds()`).
- Keine stillschweigenden Last-Writer-Wins-Merges: Bei Verstößen bricht `merge(...)` ohne Änderung am Ziel-Branch ab und liefert strukturierte `MergeConflict`-Objekte (`SHARED_VALUE_MODIFIED`, `CONCURRENT_FUNCTIONAL_ATTRIBUTE`, `EDGE_DELETED_AND_ANNOTATED`, `CONCURRENT_SCHEMA_RENAME`, `CONCURRENT_CONSTRAINT_CHANGE`, `SCHEMA_CONSTRAINT_DATA_INCOMPATIBLE`).
- Konflikte werden erst durch einen expliziten Merge-Commit (`resolveMerge(...)` mit zwei Parent-IDs) aufgelöst.

## Ausblick auf Stufe 5 & 7

- `InMemoryVersionedDatabase` zeigt strukturelle Teilung bereits auf Branch-Ebene (`T16`); pro Commit werden die Snapshots in Engine A weiterhin als isolierte Map-Kopien vorgehalten. Stufe 7 (Engine C mit Persistent Trees / HAMT / Prolly) löst die speichereffiziente Teilung auf Sub-Graph-Ebene über Commits hinweg.
