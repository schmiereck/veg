# ADR-0002: Grenzen des minimalen Bootstrap-Kerns

- **Status:** Akzeptiert für Stufe 1
- **Datum:** 2026-11-17
- **Kontext:** Hypothese `H1`, Anforderung `M-05`, Startauftrag Punkt 5

## Problemstellung

Anforderung `M-05` verlangt, dass Typdefinitionen und Constraints als reguläre Daten im Graph repräsentiert und versioniert werden, während nur ein minimaler Bootstrap-Kern hartcodiert bleibt. Eine naive vollständige Selbstbeschreibung („alles muss durch andere Graph-Entities definiert sein“) führt unweigerlich in endlose Rekursion (z. B. welchen Typ hat `META_TYPE`, und wie werden `HAS_NAME` oder `HAS_DOMAIN` vor ihrer eigenen Definition validiert?).

## Implementierter Bootstrap-Kern (`BootstrapIds`)

Die in [BootstrapIds.java](file:///C:/Users/thomas/Projekte/veg/veg-model/src/main/java/io/veg/model/BootstrapIds.java) reservierten IDs (`1..999`) begrenzen den hartcodierten Kern exakt auf vier Gruppen:

1. **Meta-Typen (`1..6`):**
   - `META_TYPE` (`#1`): Selbstreferenzieller Wurzeltyp der Meta-Ebene (`typeId = #1`).
   - `META_OBJECT_TYPE` (`#2`), `META_VALUE_TYPE` (`#3`), `META_PREDICATE_TYPE` (`#4`), `META_EDGE_TYPE` (`#5`).
   - `SYSTEM_IDENTIFIER_VALUE_TYPE` (`#6`): Werttyp für Schema-Namen (`STRING`).
2. **Skalartypen (`10..15`):**
   - `SCALAR_STRING`, `SCALAR_INT`, `SCALAR_DECIMAL`, `SCALAR_BOOLEAN`, `SCALAR_BINARY`, `SCALAR_TIMESTAMP` (korrespondieren 1:1 mit dem Java-`enum ValueType` und dem `sealed interface Value`).
3. **Strukturelle Meta-Prädikate (`20..23`):**
   - `HAS_NAME`, `HAS_SCALAR_TYPE`, `HAS_DOMAIN`, `HAS_RANGE`.
4. **Domain-/Range-Wildcards (`30..33`):**
   - `ANY_ENTITY`, `ANY_OBJECT`, `ANY_VALUE`, `ANY_EDGE` (notwendig, damit Meta-Prädikate und generische Prädikate wie `supportedBy` auf beliebige Edges oder Entities verweisen können, solange noch keine Typvererbung existiert).

### Dokumentierte nicht-selbstbeschreibbare Ausnahmen

Folgende Logik liegt bewusst **im Code der Engine** und nicht als interpretierbare Regel im Graph:
1. **Bootstrap-Initialisierung (`bootstrapKernel()`):** Das Anlegen der Bootstrap-Knoten (`#1..#33`) und ihrer initialen Kanten (`#100+`) umgeht die regulären `define*`-Prüfungen, um das Henne-Ei-Problem beim Systemstart zu lösen. Danach sind alle Bootstrap-Elemente regulär über `GraphReader` abfragbar.
2. **Skalare Wert-Payload-Prüfung:** Die Abbildung `ValueType <-> Value` (`StringValue`, `IntValue`, ...) sowie der Abgleich über `HAS_SCALAR_TYPE` ist in Code gegossen.
3. **Semantik der Constraint-Prüfung (`HAS_DOMAIN` / `HAS_RANGE` / Wildcards):** Dass eine Kante mit Prädikat `HAS_DOMAIN` bedeutet *„die Engine muss `source.typeId` vor dem Erzeugen einer Edge gegen dieses Ziel prüfen“*, ist fest codierte Validierungssemantik der Engine.
4. **Schutz vor Mutation des Bootstrap-Kerns:** `disconnect` lehnt das Entfernen von Bootstrap-Kanten (`id <= 999`) und Schema-Strukturkanten (`HAS_NAME`, `HAS_SCALAR_TYPE`, `HAS_DOMAIN`, `HAS_RANGE`) ab.

## Offene Forschungsfrage für Stufe 3

In Stufe 3 kommen `Commit`- und `Branch`-Metadaten (`actor`, `reason`, `timestamp`, `parentCommit`) hinzu. Wir prüfen dort experimentell, ob Commit-Metadaten als unveränderlicher Graph-Subgraph plus minimalem physischen Commit-Envelope modelliert werden können, ohne zirkuläre Schreibabhängigkeiten („ein Commit braucht eine Commit-Entity, um sich selbst zu committen“) auszulösen.
