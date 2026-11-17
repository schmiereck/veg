# ADR-0006: Graph-Basierte Zugriffskontrolle ("Verbieten gewinnt") & Semantische Prädikat-Inferenz

- **Status:** Akzeptiert (Stufe 5)
- **Datum:** 2026-11-17

## Kontext

In Stufe 5 benötigt VEG eine feingranulare, versionierte Zugriffskontrolle auf Entity-, Relations- und Attributebene (`S-01`–`S-03`, `S-05`, `T13`–`T14`) sowie minimale semantische Prädikatregeln (`M-12`), ohne den im Graph persistierten Bootstrap-Kern unnötig aufzublähen oder die Trennung zwischen expliziten Kanten und abgeleiteten Aussagen aufzuweichen.

Für Konflikte zwischen erlaubenden und verbietenden Berechtigungsregeln gilt die explizite Architekturentscheidung **"Verbieten gewinnt" (Deny Wins)** in Kombination mit **Default Deny**.

## Entscheidung

1. **Repräsentation im Graph & Namensraum-Trennung (`BootstrapIds` `50..69`):**
   - Principals (`META_PRINCIPAL_TYPE`), ACL-Einträge (`META_ACL_ENTRY_TYPE`) und Propagationsregeln (`META_PROPAGATION_RULE_TYPE`) werden als reguläre typisierte Entities/Kanten im versionierten Graph gespeichert und durchlaufen Savepoints, Commits, Reverts, Branches, Merges und `replayAsOf` identisch zu Fachdaten.
   - `MutableGraphState` führt einen eigenen `principalByName`-Index, damit Principal-IDs (`"Person"`) niemals mit Schema-Typnamen kollidieren.
2. **Globale Evaluationsreihenfolge in `PermissionEvaluator` ("Verbieten gewinnt"):**
   - **Default Deny:** Ohne passenden direkten oder propagierten `ALLOW`-Eintrag ist jeder Zugriff verweigert (`DEFAULT_DENY`).
   - **Deny Wins ohne Kurzschluss auf ALLOW:** Die Evaluation bricht nach einem gefundenen `ALLOW` (selbst nach `DIRECT_ALLOW` auf dem Zielknoten) **nicht** ab, sondern durchläuft zyklussicher via BFS alle Gruppen-Hierarchien (`MEMBER_OF_GROUP`), Typ-Scopes (`typeId`) sowie alle über `PropagationRule` (`OUTGOING`/`INCOMING`/`BOTH`) erreichbaren Vorfahren. Tritt irgendwo ein passender `DENY` auf, gewinnt dieser deterministisch (`DIRECT_DENY` bzw. `INHERITED_DENY`).
   - **Kanten & Attribute:** Bei Kanten (`Edge`) prüft `PermissionEvaluator` auf `DENY` auf Kante, Prädikat, Quelle und Ziel (`Verbieten gewinnt`); `READ` auf einer Kante gilt nur dann als abgeleitet lesbar, wenn beide Endpunkte lesbar sind und keines der Elemente verbietet.
3. **Zeitreihen-Sicherheit (`T14`) & Branch-Restriktionen (`S-05`):**
   - `InMemoryVersionedDatabase` liefert akteursspezifische `AuthorizedGraphReader`-Sichten (`authorizedCurrentSnapshot`, `authorizedBranchSnapshot`, `authorizedAsOf`).
   - Beim historischen Zugriff `authorizedAsOf(commitId, actor)` prüft `AuthorizedGraphReaderImpl` zusätzlich gegen den aktuellen `HEAD` des Ziel-Branches: Wurde der Zugriff dort widerrufen (`HISTORICAL_HEAD_DENY`) oder ist der Branch eingeschränkt (`BRANCH_RESTRICTED`), bleibt der historische Wert verschlossen.
4. **Abgeleitete Aussagen zur Abfragezeit (`SemanticInferenceEngine`, `M-12`):**
   - `HAS_INVERSE_PREDICATE` (symmetrisch ausgewertet) und `HAS_SUPER_PREDICATE` (zyklussicher transitiv) materialisieren **keine** zusätzlichen Kanten im Graph.
   - `GraphReader.findOutgoingEdges(...)` liefert ausschließlich explizit gespeicherte Kanten, während `findDerivedOutgoingAssertions(...)` und `findAllOutgoingAssertions(...)` Ergebnisse eindeutig mit `AssertionOrigin` (`EXPLICIT`, `DERIVED_INVERSE`, `DERIVED_SUPER_PREDICATE`) kennzeichnen und im `AuthorizedGraphReader` nach denselben `READ`-Rechten gefiltert werden.

## Konsequenzen

- **Vorteile:**
  - Deterministische, vollständig erklärbare Entscheidungen (`AccessDecision` inkl. `AccessReason` und `winningPath`, `S-03`) ohne Sicherheitslücken durch Gruppen- oder Diamant-Graphen.
  - Zyklen in Gruppen oder Datenrelationen führen durch `visited`-Sets niemals zu Endlosschleifen.
  - ACLs und Prädikatregeln sind automatisch branch-, merge- und revertfähig.
- **Nachteile / Bewusste Grenzen:**
  - `T14` verhindert beim Löschen eines Einzelobjekts nur dann historische Lesezugriffe, wenn ein Element noch am `HEAD` existiert oder sein historischer Typ bzw. sein Prädikat am `HEAD` explizit verboten (`DENY`) wurde.
  - Für große ACL-Bestände und komplexe Graph-Traversals in Engine B/C (Stufe 7) lohnt sich zukünftig ein inkrementeller Permission- oder Reachability-Cache.
