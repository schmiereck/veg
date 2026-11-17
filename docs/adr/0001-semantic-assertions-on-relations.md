# ADR-0001: Modellierung semantischer Aussagen auf Relationen

- **Status:** Bestätigt und experimentell validiert (Stufe 2)
- **Datum:** 2026-11-17
- **Kontext:** Hypothese `H3`, Anforderungen `M-02`, `M-10`, `M-11`, `S-07`

## Problemstellung

Im VEG-Modell soll eine fachliche Verbindung zwischen zwei Entitäten `A` und `B` (z. B. Person und Projekt, oder Person und Vorname) nicht nur als rohe, eigenschaftslose Kante existieren, sondern:
1. mehrere semantische Bedeutungen zwischen denselben Endpunkten ausdrücken können (z. B. `worksOn` und `contributesTo`),
2. Metadaten wie `weight`, `confidence`, Gültigkeitszeit oder Provenienz (`supportedBy -> SourceDocument`) tragen und unabhängig versioniert werden können,
3. in Stufe 4 sauber per Drei-Wege-Merge zusammengeführt werden können.

## Untersuchte Optionen

### Option A: Explizite `Connection`-Entity + separate `Assertion`-Entities

```text
Connection #4711: A -> B
  ├── Assertion #1: predicate = worksOn,       weight = 1.0, confidence = 0.95
  └── Assertion #2: predicate = contributesTo, weight = 0.8, confidence = 0.90
```

- **Vorteile:**
  - Klare Gruppierung aller Behauptungen zwischen genau einem Paar `(A, B)`.
  - Gemeinsame Metadaten (z. B. ein physischer Kontakt oder gemeinsames Dokument) könnten an der `Connection` hängen.
- **Nachteile:**
  - Zusätzliche Indirektion (`Connection` + `Assertion`-Knoten + Kanten) bereits für einfachste Attribute wie `Person --hasFirstName--> "Thomas"`.
  - Unnötiger Speicher- und Traversal-Overhead in Engine A/B, wenn 95 % aller Kanten genau eine Aussage darstellen.
  - Höhere Merge-Komplexität: Wann entsteht eine gemeinsame `Connection #4711`, wenn zwei Branches unabhängig `A --worksOn--> B` und `A --contributesTo--> B` anlegen? Zwei unterschiedliche `Connection`-IDs würden die Gruppierung brechen.

### Option B: Jede semantische Aussage ist direkt eine First-Class `Edge`-Entity (Reifikation per eigener `EntityId`)

```text
Edge #100: (A, worksOn,       B)  --supportedBy--> SourceDoc #900
                                  --hasConfidence--> 0.95
Edge #101: (A, contributesTo, B)  --hasConfidence--> 0.90
```

- **Vorteile:**
  - Minimaler Kern (`H1`): Keine gesonderte `Connection`-Primitive nötig; jede `Edge` hat automatisch eine stabile `EntityId` (`M-02`).
  - Einfache Kanten (`hasFirstName`) haben null zusätzlichen Struktur-Overhead gegenüber einem simplen Graphen, lassen sich aber bei Bedarf jederzeit per `supportedBy`, `hasConfidence` oder `hasWeight` annotieren.
  - Unabhängige Prädikate (`worksOn` vs. `contributesTo`) besitzen getrennte Lebenszyklen und erzeugen beim Parallel-Anlegen in zwei Branches keine künstliche Konfliktstelle auf einer gemeinsamen `Connection`-Entity.
- **Nachteile / Risiken:**
  - Wenn zwei verschiedene Aussagen tatsächlich eine gemeinsame übergeordnete „Beziehungshülse“ teilen sollen, muss ein explizites fachliches Verbindungsobjekt (z. B. `ProjectAssignment`) modelliert werden.
  - Die automatische Verhinderung doppelter identischer Aussagen `(source, predicate, target)` ohne `Connection`-Container erfordert in Stufe 2 eine bewusste Entscheidung über Kanten-Multiplizität vs. Uniqueness.

## Entscheidung (Stufe 1)

Wir implementieren **Option B** als Fundament für Stufe 1:
- Jede `Edge` ist eine eigenständige Entity (`EntityKind.EDGE`) mit eigener `EntityId`.
- Aussagen-Metadaten wie `supportedBy(ANY_EDGE -> SourceDocument)` und `hasConfidence(ANY_EDGE -> Confidence)` werden als reguläre ausgehende Kanten dieser `Edge` modelliert (verifiziert in Test `T04`).

## Ergebnis des Vergleichsexperiments (Stufe 2)

Das in `AssertionModelComparisonRunner` durchgeführte Experiment über `5.000` Paare bei Dichten `k ∈ {1, 2, 5}` Aussagen pro Paar (inkl. `weight`, `confidence`, `supportedBy -> SourceDocument`, Details in [stage2-assertions/analysis.md](../experiments/stage2-assertions/analysis.md)) bestätigt **Option B (`Edge = Assertion`)** eindeutig:

- **Struktur- & Speicherersparnis:** Option B benötigt **~19–33 % weniger Heap pro Aussage** (`~2.110–2.222 B` vs. `~2.671–3.304 B`) sowie **~24–35 % weniger Entities** (`8,23–9,16` vs. `10,83–14,17` Entities/Aussage).
- **Lese- & Schreibperformance:** Direkte Traversals auf typisierten Edges sind **~1,4–2,1× schneller** (`900–1.900 ns` p50 vs. `1.900–3.200 ns`) und liefern höhere Schreibdurchsätze.
- **Kardinalitäts- & Multi-Source-Kompatibilität:** Über `PredicateConstraints` lässt sich pro Prädikat sauber steuern, ob doppelte `(source, predicate, target)`-Aussagen verboten sind (`uniqueSet`) oder für konkurrierende Quellen zugelassen bleiben (`unconstrained`, default).

Option B bleibt daher verbindliches Kernmodell. Option A kann bei Bedarf als reines Fachmodell (`Connection`-Objekttyp) oberhalb des Kerns modelliert werden, ohne Engine-Primitiven zu erweitern.
