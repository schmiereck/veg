# Stage 7 Experiment: Storage-Engine-Vergleich (Engine A vs. B1 vs. B2 vs. C)

Reproduzierbarer Vergleich der vier Storage-Architekturen über alle Workloads `W1`–`W7` auf identischem logischem Graph, identischer ACL-/Inferenz-Semantik (`Stage1`–`Stage6` Contract-Äquivalenz `P-01`) und demselben deterministischen Seed (`42`).

## Reproduktion

```powershell
.\mvnw.cmd -q package -DskipTests
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar storage-experiment `
  --entities 2000 --commits 50 --branches 500 --seed 42 `
  --warmup 1 --queries 400 `
  --output docs/experiments/stage7-storage-comparison/results.csv `
  --json docs/experiments/stage7-storage-comparison/results.json
```

## Artefakte

- [results.csv](results.csv) — Rohmessdaten im CSV-Format
- [results.json](results.json) — Rohmessdaten inkl. Umgebungsparametern im JSON-Format
- [analysis.md](analysis.md) — Auswertung über `W1`–`W7` und Go-/No-Go-Entscheidung für Stufe 8
