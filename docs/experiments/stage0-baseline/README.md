# Experiment: Stage 0/1 Baseline (Engine A — InMemoryGraphEngine)

## Ziel

Messung der Referenz-Baseline für **Engine A (`InMemoryGraphEngine`)** auf Java 25 LTS vor Einführung von Historie/Versionierung (Stufe 3) und speicherkompakteren Repräsentationen (Stufe 7).

## Reproduktion

```powershell
.\mvnw.cmd -q package -DskipTests
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar benchmark `
  --entities 10000 `
  --seed 42 `
  --shared-ratio 0.50 `
  --provenance-ratio 0.25 `
  --warmup 2 `
  --iterations 5 `
  --queries 2000 `
  --output docs/experiments/stage0-baseline/results.csv `
  --json docs/experiments/stage0-baseline/results.json
```

## Erzeugter Datensatz (`DeterministicGraphGenerator`)

- **Schema:** 2 Objekttypen (`Person`, `SourceDocument`), 5 Werttypen (`FirstName`, `LastName`, `Age`, `Confidence`, `DocumentTitle`), 6 Prädikate (`hasFirstName`, `hasLastName`, `hasAge`, `hasTitle`, `supportedBy`, `hasConfidence`).
- **Instanzen pro 10.000 Personen:**
  - `10.000` Personen + `500` Quelldokumente
  - 50 % geteilte `FirstName`/`LastName`-Entitäten, 50 % eindeutige Werte + jeweils ein `Age`-Wert
  - 25 % der `hasFirstName`-Edges erhalten zwei Meta-Kanten (`supportedBy` + `hasConfidence`)

## Dateien

- `results.csv` — Rohdaten aller gemessenen Durchläufe
- `results.json` — Identische Rohdaten im JSON-Format
- `analysis.md` — Auswertung der Messwerte, Hardware-/JVM-Kontext und methodische Einschränkungen
