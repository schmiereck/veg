# Glossar (VEG)

Dieses Glossar legt die verbindlichen Begriffsabgrenzungen für Code, Tests und Architekturentscheidungen fest.

| Begriff | Bedeutung im VEG-Modell | Abgrenzung |
|---|---|---|
| **`EntityId`** | Stabile, unveränderliche Identität (`long > 0`) einer Sache im Graph. Überlebt Umbenennungen, Branches und Commits. | ≠ Wertgleichheit (zwei Entities mit `"Thomas"` können unterschiedliche `EntityId`s haben). |
| **Bootstrap-Kern** | Reservierter ID-Bereich `1..999` (`BootstrapIds`) mit minimalen Meta-Typen, Basis-Skalartypen, Wildcards und Meta-Prädikaten zur Vermeidung endloser Rekursion. | ≠ Fachliches Schema (Typen wie `Person`, `FirstName`, `hasFirstName` erhalten reguläre IDs `>= 1000`). |
| **`EntityKind`** | Strukturelle Rolle eines Knotens: `META_TYPE`, `SCALAR_TYPE`, `OBJECT`, `VALUE`, `EDGE`. | Beschreibt die Engine-Rolle, nicht den fachlichen Typ (`Person`). |
| **`Value` / `ValueType`** | Streng typisierter primitiver Wert (`STRING`, `INT`, `DECIMAL`, `BOOLEAN`, `BINARY`, `TIMESTAMP`) ohne implizite Typkonvertierung. | ≠ `EntityNode` mit `kind == VALUE` (dieser verknüpft eine stabile `EntityId` mit einem `Value`-Payload). |
| **`Edge` (Relation)** | Gerichtete, typisierte Verbindung `(id, sourceId, predicateId, targetId)`. Trägt selbst eine `EntityId` und existiert zugleich als `EntityNode(kind = EDGE)`. | Kann daher Quelle oder Ziel anderer Edges sein (`edge1 --supportedBy--> sourceDoc`). |
| **Prädikat** | Als Graph-Entity (`META_PREDICATE_TYPE`) definierter Relationstyp mit expliziten `HAS_DOMAIN`- und `HAS_RANGE`-Kanten im Graph. | ≠ konkrete `Edge`-Instanz. |
| **Aussage (Assertion)** | Semantische Behauptung mit optionaler Herkunft (Provenienz), Gewichtung (`weight`) und Sicherheit (`confidence`). | Siehe [ADR-0001](adr/0001-semantic-assertions-on-relations.md) zur Abgrenzung `Connection + Assertions` vs. `Edge = Assertion`. |
| **Transaktion** *(ab Stufe 3)* | Atomarer Konsistenz- und Validierungsrahmen vor Sichtbarkeit einer Änderung. | ≠ Commit (persistenter Übergang), ≠ Rollback vor Commit (hinterlässt keine sichtbare Historie). |
| **Commit** *(ab Stufe 3)* | Unveränderlicher Zustandsübergang im Commit-DAG mit Parent(s), Akteur, Begründung, Systemzeit und Änderungssatz. | ≠ Revert (ein neuer Gegen-Commit statt Löschen der Historie). |
| **Systemzeit vs. Gültigkeitszeit** | *Systemzeit:* wann ein Commit gespeichert wurde. *Gültigkeitszeit:* wann eine Aussage fachlich galt. | Beide Achsen bleiben strikt getrennt (bitemporale Option in späteren Stufen). |
