# Coding Agent Instructions — VEG Repository

## Build & Verification
- Java 25 LTS (`java -version`), Maven Wrapper (`.\mvnw.cmd` on Windows, `./mvnw` on Linux/macOS).
- Run `.\mvnw.cmd clean verify` after structural changes across all modules before committing.
- Preserve modularity: keep `InMemoryGraphEngine` and CLI subpackages small and focused.

## Project Knowledge Database (`data/project-kb`)
- Structured project documentation (stages `0..10`, modules, glossary terms, invariants `I-01..I-28`, ADRs `0001..0010`, and research log entries) is indexed inside the persistent VEG database at `data/project-kb`.
- Prefer querying `data/project-kb` (via `veg-project-kb` MCP tools or `veg-cli db` commands — see `.agents/skills/veg-database/SKILL.md`) when answering cross-cutting questions about invariants, modules, ADRs, or research experiments, and regenerate/update it via `import-project-docs --clean` when project Markdown documentation changes significantly.
- Always supply a meaningful `--actor` and `--reason` when writing commits into a VEG database.
