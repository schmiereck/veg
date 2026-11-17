# Experiment: Stage 2 Assertion-Modell-Vergleich (`Option A` vs. `Option B`)

## Ziel

Empirischer Vergleich der beiden Modellierungsalternativen aus [ADR-0001](../../adr/0001-semantic-assertions-on-relations.md):
- **Option A (`CONNECTION_PLUS_ASSERTIONS`):** Explizite `Connection`-Entity pro `(Person, Project)`-Paar mit separaten `Assertion`-Entities (`hasPredicateTag`, `hasWeight`, `hasConfidence`, `supportedBy`).
- **Option B (`EDGE_AS_ASSERTION`):** Jede semantische Aussage ist direkt eine First-Class `Edge`-Entity (`worksOn` / `contributesTo`), die ihre Metadaten (`hasWeight`, `hasConfidence`, `supportedBy`) als ausgehende Kanten über `ANY_EDGE` trägt.

## Reproduktion

```powershell
.\mvnw.cmd -q package -DskipTests
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar assertion-experiment `
  --pairs 5000 `
  --seed 42 `
  --densities 1,2,5 `
  --warmup 1 `
  --iterations 3 `
  --queries 1000 `
  --output docs/experiments/stage2-assertions/results.csv `
  --json docs/experiments/stage2-assertions/results.json
```

## Dateien

- `results.csv` — Rohdaten aller gemessenen Durchläufe (`k = 1, 2, 5`)
- `results.json` — Identische Rohdaten im JSON-Format
- `analysis.md` — Vergleich von Speicher-, Schreib-, Abfrage- und Struktureffizienz
