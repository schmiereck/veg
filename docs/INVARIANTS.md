# Systeminvarianten (VEG)

Alle Storage-Backends (`veg-engine-memory` sowie spätere Engines B und C) müssen diese Invarianten zu jedem Zeitpunkt einhalten. Die Verifikation erfolgt über `Stage1ContractTestSuite`, `Stage2ContractTestSuite`, `Stage3ContractTestSuite` und die Property-Based Tests (`GraphInvariantsProperties`, `VersionedDatabaseProperties`).

| ID | Invariante | Beschreibung & Prüfkriterium |
|---|---|---|
| **I-01** | **Stabile, eindeutige Identität** | Jede `EntityId` ist positiv (`> 0`), wird pro Graph-Instanz höchstens einmal vergeben (ab `1000` streng monoton) und bleibt über Mutationen stabil. |
| **I-02** | **First-Class Edges** | Zu jeder aktiven `Edge(id, s, p, t)` existiert exakt ein korrespondierender `EntityNode(id, META_EDGE_TYPE, EDGE, empty)`. Dadurch ist jede Relation als Quelle oder Ziel weiterer Relationen adressierbar (`M-02`). |
| **I-03** | **First-Class Attribute Values** | Jeder Attributwert besitzt eine eigene `EntityId` und einen `EntityNode(kind = VALUE)` mit zwingend vorhandenem `Value`-Payload (`M-03`). Andere `EntityKind`s dürfen keinen Payload tragen. |
| **I-04** | **Atomare Typ- und Constraint-Integrität** | Scheiternde Schema-, Skalartyp-, Domain- oder Range-Prüfungen werfen eine typisierte `VegException` und lassen `entityCount()`, `edgeCount()` sowie alle sichtbaren Graphdaten exakt unverändert (`M-04`, `V-03`). |
| **I-05** | **Selbstbeschreibendes Schema im Graph** | Mit Ausnahme des Bootstrap-Kerns (`1..999`) liegen alle fachlichen Typen, Werttypen, Prädikate, Namen (`HAS_NAME`), Skalartypen (`HAS_SCALAR_TYPE`) sowie `HAS_DOMAIN`/`HAS_RANGE` als reguläre Knoten und Kanten im Graph (`M-05`). |
| **I-06** | **Referenzielle Kanten-Integrität** | Jede aktive `Edge` referenziert existierende `sourceId`, `predicateId` (vom Typ `META_PREDICATE_TYPE`) und `targetId`. Ein `disconnect(edgeId)` wird mit `ReferentialIntegrityException` abgelehnt, solange andere aktive Edges auf `edgeId` verweisen. |
| **I-07** | **Keine implizite Typkonvertierung** | Ein `Value` wird nur akzeptiert, wenn sein `ValueType` exakt dem über `HAS_SCALAR_TYPE` am Ziel-Werttyp hinterlegten Skalartyp entspricht. |
| **I-08** | **Unveränderlicher Bootstrap-Kern** | Bootstrap-Entitäten und strukturelle Schema-Kanten (`HAS_NAME`, `HAS_SCALAR_TYPE`, `HAS_DOMAIN`, `HAS_RANGE`, `HAS_MAX_OUTGOING`, `HAS_MAX_INCOMING`, `HAS_UNIQUE_TRIPLE`, `HAS_UNIQUE_VALUES`, `HAS_MUTABILITY`) können nicht durch reguläre `disconnect`-Aufrufe entfernt werden. |
| **I-09** | **Explizite Shared-Mutation-Semantik** | Jeder `VALUE`-Knoten besitzt eine explizite `ValueMutability` (`IMMUTABLE` als Default). In-Place-Updates via `updateValue` sind ausschließlich für benutzerdefinierte `SHARED_MUTABLE`-Knoten unter Wahrung von Skalartyp und `uniqueValues` zulässig. |
| **I-10** | **Prädikat-Kardinalität & Uniqueness** | Ein `connect` darf weder `maxOutgoing`, `maxIncoming` noch `uniqueTriple` des Prädikats überschreiten; `createValue`/`updateValue` darf bei `uniqueValues = true` keine Duplikate erzeugen. Alle Verstöße scheitern atomar. |
| **I-11** | **Unveränderliche Snapshots & Transaktions-Isolation** | Offene Transaktionsänderungen bleiben bis zum `commit()` unsichtbar; ältere Snapshots (`asOf(commitId)`) mutieren durch spätere Commits oder Schemaänderungen niemals. |
| **I-12** | **Deterministische Replay-Äquivalenz** | Das Abspielen der persistierten `Change`-Liste aller Commits von `c0` bis `cN` (`replayAsOf(cN)`) erzeugt exakt denselben Graph- und Schema-Zustand wie `asOf(cN)` (`T15`). |
| **I-13** | **Historien-erhaltender Revert** | Ein `revert(commitId)` löscht keinen bestehenden Commit, sondern erzeugt einen neuen Gegen-Commit (`V-10`); Konflikte durch spätere Abhängigkeiten scheitern atomar mit `RevertConflictException`. |
