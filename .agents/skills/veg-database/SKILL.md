---
name: veg-database
description: Inspects VEG schemas, browses ObjectType datasheets, runs GraphQueryDsl queries, inspects entity relations/commit history, and performs audited transactions against VEG databases via MCP or veg-cli.
---

# Working with VEG Databases

Use this skill whenever querying or updating the project knowledge graph at `data/project-kb` or any `PersistentDiskVersionedDatabase` directory.

## 1. Preferred Access Path: MCP Tools (`veg-project-kb`)

If the `veg-project-kb` MCP server (`.agents/plugins/veg-mcp/mcp_config.json`) is active:

1. **Inspect schema first (`veg_schema`)** to confirm exact `ObjectType` names, `ValueType` scalar types, predicate names, branches, tags, and current commit ID.
2. **Browse instances (`veg_datasheet`)** for tabular overviews, filtering (`search` or `filters`), and sorting (`sortBy`, `sortDir`).
3. **Run multi-hop queries (`veg_query`)** using `GraphQueryDsl`:
   ```sql
   MATCH (adr:ArchitectureDecision)
   JOIN (adr)-[addressesInvariant]->(inv:Invariant)
   WHERE inv.id = "I-17"
   SELECT adr.id, adr.title, inv.id, inv.title
   ORDER BY adr.id ASC
   ```
4. **Inspect one entity (`veg_entity`)** to retrieve all scalar attributes, outgoing relations, incoming relations, bitemporal validity windows, and commit history.
5. **Commit audited mutations (`veg_mutate`)** with explicit `actor` and `reason`:
   - `create_object` (with optional `idStrategy`: `SEQUENTIAL`, `UUID_V7`, `UUID_V4`), `set_attribute`, `connect`, `disconnect`, `define_object_type`, `define_value_type`, `define_predicate`.
6. **Export & 3-way merge Git patches (`veg_patch`)**:
   - `action = export` produces a deterministic plain-text `.vegpatch` diff using symbolic `@Type:` / `@Pred:` references and UUIDs.
   - `action = apply` or `merge` imports a `.vegpatch` idempotently (via SHA-256 `patch-id`), running full three-way merge when `base-commit` exists locally.

## 2. CLI Fallback (`veg-cli db`)

When MCP stdio is not active in the current session, invoke `veg-cli` directly:

```powershell
# Schema, branches, tags, and row counts
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb schema

# Tabular datasheet view (with optional --search / --as-of c3)
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb datasheet --type Invariant --search WAL

# GraphQueryDsl query
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb query --dsl "MATCH (m:Module) JOIN (m)-[dependsOnModule]->(dep:Module) SELECT m.id, dep.id"

# Inspect full entity graph & commit history
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb inspect --id 1025

# Commit history
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb commits

# Export & 3-way merge Git-friendly .vegpatch diffs (and create UUIDv7 entities)
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb export-patch --from c4 --to c5 --output diff.vegpatch
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar db --db data/project-kb apply-patch --input diff.vegpatch --mode merge
```

## 3. Importing or Launching Studio

```powershell
# Import or rebuild data/project-kb from README.md and docs/
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar import-project-docs --db data/project-kb --docs-root . --clean

# Launch VEG Access Studio browser UI
java -jar veg-cli/target/veg-cli-0.1.0-SNAPSHOT.jar studio --db data/project-kb --port 8080
```
