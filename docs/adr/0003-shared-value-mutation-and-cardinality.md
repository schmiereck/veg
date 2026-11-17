# ADR-0003: Shared-Value-Mutabilität, Uniqueness und Prädikat-Kardinalitäten

- **Status:** Akzeptiert (Stufe 2)
- **Datum:** 2026-11-17
- **Kontext:** Hypothese `H2`, Anforderungen `M-03`, `M-08`, `M-09`, `T03`, `T11`-Vorbereitung

## Problemstellung

In Stufe 1 wurde festgelegt, dass Attributwerte eigenständige Entities (`EntityKind.VALUE`) sind und von mehreren Objekten referenziert werden können. Daraus entstehen drei offene Semantikfragen vor Einführung von Historie und Branches (Stufe 3–4):

1. **Shared-Mutation-Semantik (`H2`):** Was passiert, wenn zwei Entitäten (z. B. zwei Personen) dasselbe Wertobjekt referenzieren und ein Nutzer den Wert ändert? Darf eine In-Place-Mutation stillschweigend beide Personen verändern?
2. **Eindeutige Werte (`M-08`):** Wie lassen sich fachlich eindeutige Werttypen wie `EmailAddress` erzwingen?
3. **Kardinalitäten & Kanten-Multiplizität (`M-08`, `M-09`):** Wie werden `1:1`, `1:n`, `n:m` und die Zulässigkeit mehrerer Aussagen desselben Prädikats zwischen `(A, B)` im Graph-Schema ausgedrückt?

## Entscheidung

### 1. Explizite `ValueMutability` (`IMMUTABLE` als Default vs. `SHARED_MUTABLE`)
- Jeder `EntityKind.VALUE`-Knoten trägt im Graph eine `HAS_MUTABILITY`-Kante auf `MUTABILITY_IMMUTABLE` (`#40`) oder `MUTABILITY_SHARED_MUTABLE` (`#41`).
- **Standard ist `IMMUTABLE`:** Aufrufe von `updateValue(...)` schlagen atomar mit `ImmutableValueModificationException` fehl. Um das Attribut einer Person zu ändern, muss ihre Kante umgehängt werden (`disconnect` + neues `createValue`/`connect`), sodass ein versehentliches globales Alias-Überschreiben ausgeschlossen ist.
- **Explizites `SHARED_MUTABLE`:** Nur ausdrücklich als `SHARED_MUTABLE` erzeugte Wert-Entities erlauben `updateValue(...)`, wobei die Änderung sofort für alle referenzierenden Kanten sichtbar wird. System- und Bootstrap-Wertknoten (`HAS_NAME`, Schema-Flags) sind immer unveränderlich.

### 2. Typisierte Eindeutigkeit auf Werttypen (`uniqueValues`)
- Werttypen können über `defineValueType(name, scalarType, true)` als eindeutig definiert werden (abgelegt als `HAS_UNIQUE_VALUES`-Kante im Graph).
- Sowohl `createValue` als auch `updateValue` prüfen atomar auf Wertgleichheit innerhalb dieses Werttyps (`UniquenessViolationException`). Beim erfolgreichen Update eines `SHARED_MUTABLE`-Wertes wird der alte Wert sofort wieder freigegeben.

### 3. Prädikat-Kardinalitäten & Triple-Uniqueness im Graph (`PredicateConstraints`)
- Jedes Prädikat speichert `HAS_MAX_OUTGOING`, `HAS_MAX_INCOMING` und `HAS_UNIQUE_TRIPLE` als reguläre Graph-Kanten (`PredicateConstraints`):
  - `unconstrained()` *(Default)*: erlaubt auch mehrere konkurrierende Aussagen `(A, pred, B)` aus unterschiedlichen Quellen.
  - `uniqueSet()`: verhindert doppelte `(A, pred, B)`-Tripel (`n:m`-Menge).
  - `manyToOne()`: `maxOutgoing = 1, uniqueTriple = true`.
  - `oneToMany()`: `maxIncoming = 1, uniqueTriple = true`.
  - `oneToOne()`: `maxOutgoing = 1, maxIncoming = 1, uniqueTriple = true`.
- Verstöße werden vor jeder Graphänderung atomar über `CardinalityViolationException` bzw. `UniquenessViolationException` abgewiesen.

## Konsequenzen & Ausblick auf Stufe 3/4

- **Vorbereitung für `T11` (Merge-Konflikt an gemeinsamem Wertobjekt):** Durch die explizite Unterscheidung `IMMUTABLE` vs. `SHARED_MUTABLE` kann der 3-Wege-Merge in Stufe 4 gezielt erkennen, ob zwei Branches dasselbe `SHARED_MUTABLE`-Wertobjekt widersprüchlich geändert haben.
- **Speicherkosten-Hinweis:** Das explizite Ablegen einer `HAS_MUTABILITY`-Kante pro `VALUE`-Entity macht die Engine A semantisch komplett uniform, erhöht aber den Overhead pro einfachem Wertknoten (mitgemessen in Stufe 2). Engine B/C (Stufe 7) darf dieses Flag physisch in 1 Bit/Header-Feld kodieren, solange `GraphReader.resolveValueMutability(...)` dieselbe Semantik liefert.
