package io.veg.cli.kb;

import io.veg.engine.GraphTransaction;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.BootstrapIds;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.EntityId;
import io.veg.model.PredicateConstraints;
import io.veg.model.Reason;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses the VEG project's Markdown documentation ({@code README.md}, {@code docs/*.md},
 * {@code docs/adr/*.md}) into a structured, queryable knowledge graph stored across
 * structured commits in a {@link VersionedGraphDatabase}.
 */
public final class ProjectKnowledgeImporter {

    private static final Pattern INVARIANT_ROW = Pattern.compile(
            "^\\|\\s*\\*\\*(I-\\d+)\\*\\*\\s*\\|\\s*(.+?)\\s*\\|\\s*(.+?)\\s*\\|$"
    );
    private static final Pattern STAGE_MENTION = Pattern.compile(
            "(?i)(?:Stufe|Stage)\\s+(\\d+)"
    );
    private static final Pattern ADR_FILE_PATTERN = Pattern.compile(
            "(\\d{4})-[^)\\s]+\\.md"
    );
    private static final Pattern INVARIANT_MENTION = Pattern.compile(
            "\\b(I-\\d{2})\\b"
    );

    public record ImportReport(
            int commitCount,
            CommitId headCommitId,
            String tagName,
            int documents,
            int modules,
            int stages,
            int glossaryTerms,
            int invariants,
            int adrs,
            int researchEntries
    ) {
    }

    private ProjectKnowledgeImporter() {
    }

    public static ImportReport importFromDocsRoot(VersionedGraphDatabase db, Path docsRoot) throws IOException {
        Objects.requireNonNull(db, "db must not be null");
        Objects.requireNonNull(docsRoot, "docsRoot must not be null");
        Path root = docsRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Docs root directory does not exist: " + root);
        }
        if (db.currentSnapshot().findTypeByName("Invariant").isPresent()) {
            throw new IllegalStateException("Project knowledge schema already exists in target database.");
        }

        Actor importer = Actor.of("kb-importer", "VEG Markdown Knowledge Importer");
        String branch = db.currentBranch();

        SchemaHandles schema = commitSchema(db, branch, importer);

        Map<String, EntityId> docByPath = new LinkedHashMap<>();
        Map<String, EntityId> moduleById = new LinkedHashMap<>();
        commitDocumentsAndModules(db, branch, importer, root, schema, docByPath, moduleById);

        Map<Integer, EntityId> stageByNumber = new LinkedHashMap<>();
        int glossaryCount = commitStagesAndGlossary(db, branch, importer, root, schema, docByPath, moduleById, stageByNumber);

        Map<String, EntityId> invariantById = new LinkedHashMap<>();
        commitInvariants(db, branch, importer, root, schema, docByPath, stageByNumber, invariantById);

        AdrAndResearchCounts counts = commitAdrsAndResearchLog(
                db, branch, importer, root, schema, docByPath, stageByNumber, invariantById
        );

        CommitId head = db.headCommitId();
        String tagName = "v1.0-stage10";
        if (db.findTag(tagName).isEmpty()) {
            db.createTag(tagName, head);
        }

        return new ImportReport(
                5,
                head,
                tagName,
                docByPath.size(),
                moduleById.size(),
                stageByNumber.size(),
                glossaryCount,
                invariantById.size(),
                counts.adrs(),
                counts.researchEntries()
        );
    }

    private record SchemaHandles(
            EntityId documentType,
            EntityId stageType,
            EntityId moduleType,
            EntityId invariantType,
            EntityId adrType,
            EntityId researchEntryType,
            EntityId glossaryTermType,
            EntityId codeIdVal,
            EntityId titleTextVal,
            EntityId categoryNameVal,
            EntityId statusTextVal,
            EntityId filePathTextVal,
            EntityId summaryTextVal,
            EntityId dateTextVal,
            EntityId distinctionTextVal,
            EntityId stageNumberVal,
            EntityId lineCountVal,
            EntityId predId,
            EntityId predTitle,
            EntityId predCategory,
            EntityId predStatus,
            EntityId predFilePath,
            EntityId predSummary,
            EntityId predDateText,
            EntityId predDistinction,
            EntityId predStageNumber,
            EntityId predLineCount,
            EntityId predDocumentedIn,
            EntityId predBelongsToStage,
            EntityId predImplementsStage,
            EntityId predDependsOnModule,
            EntityId predAddressesInvariant,
            EntityId predRelatedAdr
    ) {
    }

    private record AdrAndResearchCounts(int adrs, int researchEntries) {
    }

    private static SchemaHandles commitSchema(VersionedGraphDatabase db, String branch, Actor actor) {
        try (GraphTransaction tx = db.beginTransaction(
                branch, actor, Reason.of("Define VEG project knowledge graph schema"), "kb-import-c1", DataCategory.SCHEMA)) {

            EntityId documentType = tx.defineObjectType("Document");
            EntityId stageType = tx.defineObjectType("Stage");
            EntityId moduleType = tx.defineObjectType("Module");
            EntityId invariantType = tx.defineObjectType("Invariant");
            EntityId adrType = tx.defineObjectType("ArchitectureDecision");
            EntityId researchEntryType = tx.defineObjectType("ResearchEntry");
            EntityId glossaryTermType = tx.defineObjectType("GlossaryTerm");

            EntityId codeIdVal = tx.defineValueType("CodeId", ValueType.STRING, false);
            EntityId titleTextVal = tx.defineValueType("TitleText", ValueType.STRING, false);
            EntityId categoryNameVal = tx.defineValueType("CategoryName", ValueType.STRING, false);
            EntityId statusTextVal = tx.defineValueType("StatusText", ValueType.STRING, false);
            EntityId filePathTextVal = tx.defineValueType("FilePathText", ValueType.STRING, false);
            EntityId summaryTextVal = tx.defineValueType("SummaryText", ValueType.STRING, false);
            EntityId dateTextVal = tx.defineValueType("DateText", ValueType.STRING, false);
            EntityId distinctionTextVal = tx.defineValueType("DistinctionText", ValueType.STRING, false);
            EntityId stageNumberVal = tx.defineValueType("StageNumber", ValueType.INT, false);
            EntityId lineCountVal = tx.defineValueType("LineCount", ValueType.INT, false);

            PredicateConstraints single = PredicateConstraints.manyToOne();
            PredicateConstraints relSet = PredicateConstraints.uniqueSet();

            EntityId predId = tx.definePredicate("id", BootstrapIds.ANY_OBJECT, codeIdVal, single);
            EntityId predTitle = tx.definePredicate("title", BootstrapIds.ANY_OBJECT, titleTextVal, single);
            EntityId predCategory = tx.definePredicate("category", BootstrapIds.ANY_OBJECT, categoryNameVal, single);
            EntityId predStatus = tx.definePredicate("status", BootstrapIds.ANY_OBJECT, statusTextVal, single);
            EntityId predFilePath = tx.definePredicate("filePath", BootstrapIds.ANY_OBJECT, filePathTextVal, single);
            EntityId predSummary = tx.definePredicate("summary", BootstrapIds.ANY_OBJECT, summaryTextVal, single);
            EntityId predDateText = tx.definePredicate("dateText", BootstrapIds.ANY_OBJECT, dateTextVal, single);
            EntityId predDistinction = tx.definePredicate("distinction", glossaryTermType, distinctionTextVal, single);
            EntityId predStageNumber = tx.definePredicate("stageNumber", stageType, stageNumberVal, single);
            EntityId predLineCount = tx.definePredicate("lineCount", documentType, lineCountVal, single);

            EntityId predDocumentedIn = tx.definePredicate("documentedIn", BootstrapIds.ANY_OBJECT, documentType, relSet);
            EntityId predBelongsToStage = tx.definePredicate("belongsToStage", BootstrapIds.ANY_OBJECT, stageType, relSet);
            EntityId predImplementsStage = tx.definePredicate("implementsStage", moduleType, stageType, relSet);
            EntityId predDependsOnModule = tx.definePredicate("dependsOnModule", moduleType, moduleType, relSet);
            EntityId predAddressesInvariant = tx.definePredicate("addressesInvariant", adrType, invariantType, relSet);
            EntityId predRelatedAdr = tx.definePredicate("relatedAdr", researchEntryType, adrType, relSet);

            tx.commit();
            return new SchemaHandles(
                    documentType, stageType, moduleType, invariantType, adrType, researchEntryType, glossaryTermType,
                    codeIdVal, titleTextVal, categoryNameVal, statusTextVal, filePathTextVal, summaryTextVal,
                    dateTextVal, distinctionTextVal, stageNumberVal, lineCountVal,
                    predId, predTitle, predCategory, predStatus, predFilePath, predSummary,
                    predDateText, predDistinction, predStageNumber, predLineCount,
                    predDocumentedIn, predBelongsToStage, predImplementsStage,
                    predDependsOnModule, predAddressesInvariant, predRelatedAdr
            );
        }
    }

    private static void commitDocumentsAndModules(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Path root,
            SchemaHandles s,
            Map<String, EntityId> docByPath,
            Map<String, EntityId> moduleById
    ) throws IOException {
        List<Path> markdownFiles = collectMarkdownDocuments(root);
        try (GraphTransaction tx = db.beginTransaction(
                branch, actor, Reason.of("Import project Markdown documents and Maven modules"), "kb-import-c2", DataCategory.REFERENCE)) {

            for (Path file : markdownFiles) {
                String relPath = root.relativize(file).toString().replace('\\', '/');
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                String title = extractH1Title(lines, file.getFileName().toString());
                String category = classifyDocument(relPath);
                String summary = extractLeadSummary(lines);

                EntityId docId = tx.createObject(s.documentType());
                setStringAttr(tx, docId, s.predId(), s.codeIdVal(), relPath);
                setStringAttr(tx, docId, s.predTitle(), s.titleTextVal(), title);
                setStringAttr(tx, docId, s.predCategory(), s.categoryNameVal(), category);
                setStringAttr(tx, docId, s.predFilePath(), s.filePathTextVal(), relPath);
                setStringAttr(tx, docId, s.predSummary(), s.summaryTextVal(), summary);
                setIntAttr(tx, docId, s.predLineCount(), s.lineCountVal(), lines.size());
                docByPath.put(relPath, docId);
            }

            List<ModuleSeed> modules = List.of(
                    new ModuleSeed("veg-model", "VEG Core Domain Model", "Core Primitives",
                            "Unveränderliche Java-25-Records und Sealed Interfaces (EntityId, Value, EntityNode, Edge, Commit, BootstrapIds).", List.of()),
                    new ModuleSeed("veg-engine-api", "VEG Engine Interfaces & Query/RDF Layer", "Engine Contract & Query",
                            "Storage-agnostische APIs (GraphReader, GraphSession, VersionedGraphDatabase, GraphQuery, GraphQueryDsl, RdfGraphExporter).", List.of("veg-model")),
                    new ModuleSeed("veg-engine-memory", "VEG In-Memory Reference Engines (A, B1/B2, C)", "Storage & Versioning",
                            "Engine A (InMemory), Engine B1/B2 (Compact CSR + Checkpoints), Engine C (Persistent Radix-32-Trie), 3-Wege-Merge, ACLs.", List.of("veg-engine-api", "veg-model")),
                    new ModuleSeed("veg-engine-persistent", "VEG Native Persistent WAL & Disk Engine", "Disk Persistence",
                            "CRC32C-gesichertes Append-Only WAL, atomare refs.meta, GZIP-Checkpoints, Kaltarchivierung, Idempotenz und Crash-Recovery.", List.of("veg-engine-memory", "veg-engine-api")),
                    new ModuleSeed("veg-test-fixtures", "VEG Backend-Agnostic Contract Test Suites", "Verification",
                            "Stage1–Stage6 & Stage10 Contract-Suites, Deterministische Generatoren, LUBM/BSBM RDF-Testdatensätze.", List.of("veg-engine-api")),
                    new ModuleSeed("veg-benchmarks", "VEG Reproducible Benchmark Runners", "Experiments",
                            "Workloads W1–W7, Assertion-Modellvergleich (Stage 2) und Storage-Engine-Vergleich (Stage 7).", List.of("veg-engine-memory", "veg-test-fixtures")),
                    new ModuleSeed("veg-cli", "VEG CLI, MCP Server & Access Studio", "Tooling & UI",
                            "Kommandozeile, Knowledge-Base-Importer, MCP-Stdio-Server für Coding-Agents und VEG Access Studio.", List.of("veg-benchmarks", "veg-engine-persistent", "veg-test-fixtures"))
            );

            EntityId archDoc = docByPath.get("docs/ARCHITECTURE.md");
            for (ModuleSeed m : modules) {
                EntityId id = tx.createObject(s.moduleType());
                setStringAttr(tx, id, s.predId(), s.codeIdVal(), m.id());
                setStringAttr(tx, id, s.predTitle(), s.titleTextVal(), m.title());
                setStringAttr(tx, id, s.predCategory(), s.categoryNameVal(), m.category());
                setStringAttr(tx, id, s.predFilePath(), s.filePathTextVal(), m.id() + "/pom.xml");
                setStringAttr(tx, id, s.predSummary(), s.summaryTextVal(), m.summary());
                setStringAttr(tx, id, s.predStatus(), s.statusTextVal(), "Active (Java 25 LTS)");
                if (archDoc != null) {
                    tx.connect(id, s.predDocumentedIn(), archDoc);
                }
                moduleById.put(m.id(), id);
            }
            for (ModuleSeed m : modules) {
                EntityId from = moduleById.get(m.id());
                for (String dep : m.dependencies()) {
                    EntityId to = moduleById.get(dep);
                    if (to != null) {
                        tx.connect(from, s.predDependsOnModule(), to);
                    }
                }
            }
            tx.commit();
        }
    }

    private record ModuleSeed(String id, String title, String category, String summary, List<String> dependencies) {
    }

    private static int commitStagesAndGlossary(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Path root,
            SchemaHandles s,
            Map<String, EntityId> docByPath,
            Map<String, EntityId> moduleById,
            Map<Integer, EntityId> stageByNumber
    ) throws IOException {
        try (GraphTransaction tx = db.beginTransaction(
                branch, actor, Reason.of("Import roadmap stages (0..10) and VEG glossary"), "kb-import-c3", DataCategory.REFERENCE)) {

            EntityId conceptDoc = docByPath.get("docs/CONCEPT.md");
            EntityId glossaryDoc = docByPath.get("docs/GLOSSARY.md");

            List<StageSeed> stages = List.of(
                    new StageSeed(0, "Phase I: Semantischer Kern", "Done", "Multi-Modul-Build, CI, Test-Oracle, Baseline-Harness", List.of("veg-model", "veg-benchmarks", "veg-cli")),
                    new StageSeed(1, "Phase I: Semantischer Kern", "Done", "Minimalgraph, Bootstrap-Selbstbeschreibung (1..999), strenge Typprüfung, First-Class Edges", List.of("veg-model", "veg-engine-api", "veg-engine-memory", "veg-test-fixtures")),
                    new StageSeed(2, "Phase I: Semantischer Kern", "Done", "Identität vs. Shared Mutation (IMMUTABLE/SHARED_MUTABLE), Kardinalitäts-Constraints & Assertion-Experiment", List.of("veg-engine-memory", "veg-benchmarks")),
                    new StageSeed(3, "Phase II: Versionierung & Merge", "Done", "Atomare Transaktionen, Savepoints, unveränderliche Snapshots, Commit-DAG, asOf, diff, revert, replayAsOf", List.of("veg-engine-memory", "veg-test-fixtures")),
                    new StageSeed(4, "Phase II: Versionierung & Merge", "Done", "Branches, Tags, selektives Staging (V-06) und konfliktsicherer 3-Wege-Merge inkl. DataCategory-Filterung", List.of("veg-engine-memory", "veg-test-fixtures")),
                    new StageSeed(5, "Phase III: Sicherheit & Abfragen", "Done", "Graph-gespeicherte ACLs (Default Deny, Verbieten gewinnt), zyklussichere Propagation, authorizedAsOf & Inferenz", List.of("veg-engine-memory", "veg-test-fixtures")),
                    new StageSeed(6, "Phase III: Sicherheit & Abfragen", "Done", "Storage-unabhängige Fluent-Query-API (GraphQuery), GraphQueryDsl, Turtle/N-Triples Import/Export, LUBM/BSBM", List.of("veg-engine-api", "veg-test-fixtures")),
                    new StageSeed(7, "Phase IV: Eigene Storage-Engine", "Done", "Engine A vs. B1/B2 (CSR) vs. C (Persistent Radix-32-Trie) Äquivalenz & W1–W7-Benchmark-Entscheidung", List.of("veg-engine-memory", "veg-benchmarks")),
                    new StageSeed(8, "Phase IV: Eigene Storage-Engine", "Done", "PersistentDiskVersionedDatabase mit CRC32C-WAL, refs.meta, GZIP-Checkpoints, Kaltarchiv & Crash-Recovery", List.of("veg-engine-persistent")),
                    new StageSeed(9, "Phase V: Optionale Adapter (P3)", "Optional (Deferred P3)", "Optionaler Oracle-/SQL-/Outbox-CDC-Adapter — bewusst nach hinten priorisiert zugunsten nativer Engine", List.of()),
                    new StageSeed(10, "Phase V: Reifung & Optionale Adapter", "Done", "Multi-User MVCC, Idempotenz, Typ-Indizes & SnapshotAclCache, Bitemporalität, Cherry-Pick & DSGVO-Redaktion", List.of("veg-engine-memory", "veg-engine-persistent"))
            );

            for (StageSeed st : stages) {
                EntityId id = tx.createObject(s.stageType());
                setStringAttr(tx, id, s.predId(), s.codeIdVal(), String.format("Stage-%02d", st.number()));
                setStringAttr(tx, id, s.predTitle(), s.titleTextVal(), "Stufe " + st.number());
                setStringAttr(tx, id, s.predCategory(), s.categoryNameVal(), st.phase());
                setStringAttr(tx, id, s.predStatus(), s.statusTextVal(), st.status());
                setStringAttr(tx, id, s.predSummary(), s.summaryTextVal(), st.summary());
                setIntAttr(tx, id, s.predStageNumber(), s.stageNumberVal(), st.number());
                if (conceptDoc != null) {
                    tx.connect(id, s.predDocumentedIn(), conceptDoc);
                }
                for (String mod : st.modules()) {
                    EntityId modId = moduleById.get(mod);
                    if (modId != null) {
                        tx.connect(modId, s.predImplementsStage(), id);
                    }
                }
                stageByNumber.put(st.number(), id);
            }

            int glossaryCount = 0;
            Path glossaryFile = root.resolve("docs/GLOSSARY.md");
            if (Files.isRegularFile(glossaryFile)) {
                List<String> lines = Files.readAllLines(glossaryFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("|") || trimmed.startsWith("| Begriff") || trimmed.startsWith("|---")) {
                        continue;
                    }
                    List<String> cols = splitMarkdownTableRow(trimmed);
                    if (cols.size() >= 3) {
                        String term = cleanMarkdown(cols.get(0));
                        String meaning = cleanMarkdown(cols.get(1));
                        String distinction = cleanMarkdown(cols.get(2));
                        if (!term.isBlank()) {
                            EntityId gtId = tx.createObject(s.glossaryTermType());
                            setStringAttr(tx, gtId, s.predId(), s.codeIdVal(), term);
                            setStringAttr(tx, gtId, s.predTitle(), s.titleTextVal(), term);
                            setStringAttr(tx, gtId, s.predSummary(), s.summaryTextVal(), meaning);
                            setStringAttr(tx, gtId, s.predDistinction(), s.distinctionTextVal(), distinction);
                            if (glossaryDoc != null) {
                                tx.connect(gtId, s.predDocumentedIn(), glossaryDoc);
                            }
                            connectStageMentions(tx, gtId, s.predBelongsToStage(), term + " " + meaning, stageByNumber);
                            glossaryCount++;
                        }
                    }
                }
            }
            tx.commit();
            return glossaryCount;
        }
    }

    private record StageSeed(int number, String phase, String status, String summary, List<String> modules) {
    }

    private static void commitInvariants(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Path root,
            SchemaHandles s,
            Map<String, EntityId> docByPath,
            Map<Integer, EntityId> stageByNumber,
            Map<String, EntityId> invariantById
    ) throws IOException {
        Path invFile = root.resolve("docs/INVARIANTS.md");
        if (!Files.isRegularFile(invFile)) {
            return;
        }
        EntityId invDoc = docByPath.get("docs/INVARIANTS.md");
        List<String> lines = Files.readAllLines(invFile, StandardCharsets.UTF_8);

        try (GraphTransaction tx = db.beginTransaction(
                branch, actor, Reason.of("Import system invariants I-01..I-28 from INVARIANTS.md"), "kb-import-c4", DataCategory.REFERENCE)) {
            for (String line : lines) {
                Matcher m = INVARIANT_ROW.matcher(line.trim());
                if (!m.matches()) {
                    continue;
                }
                String code = m.group(1).trim();
                String title = cleanMarkdown(m.group(2));
                String desc = cleanMarkdown(m.group(3));

                EntityId invEntity = tx.createObject(s.invariantType());
                setStringAttr(tx, invEntity, s.predId(), s.codeIdVal(), code);
                setStringAttr(tx, invEntity, s.predTitle(), s.titleTextVal(), title);
                setStringAttr(tx, invEntity, s.predCategory(), s.categoryNameVal(), inferInvariantCategory(code));
                setStringAttr(tx, invEntity, s.predStatus(), s.statusTextVal(), "Enforced by Contract Suites");
                setStringAttr(tx, invEntity, s.predSummary(), s.summaryTextVal(), desc);
                if (invDoc != null) {
                    tx.connect(invEntity, s.predDocumentedIn(), invDoc);
                }
                Integer stageNum = inferInvariantStage(code);
                if (stageNum != null && stageByNumber.containsKey(stageNum)) {
                    tx.connect(invEntity, s.predBelongsToStage(), stageByNumber.get(stageNum));
                }
                invariantById.put(code, invEntity);
            }
            tx.commit();
        }
    }

    private static AdrAndResearchCounts commitAdrsAndResearchLog(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Path root,
            SchemaHandles s,
            Map<String, EntityId> docByPath,
            Map<Integer, EntityId> stageByNumber,
            Map<String, EntityId> invariantById
    ) throws IOException {
        Map<String, EntityId> adrByNumber = new LinkedHashMap<>();
        int adrCount = 0;
        int researchCount = 0;

        try (GraphTransaction tx = db.beginTransaction(
                branch, actor, Reason.of("Import ADRs (0001..0010) and research log entries"), "kb-import-c5", DataCategory.REFERENCE)) {

            Path adrDir = root.resolve("docs/adr");
            if (Files.isDirectory(adrDir)) {
                List<Path> adrFiles;
                try (Stream<Path> walk = Files.list(adrDir)) {
                    adrFiles = walk
                            .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
                            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                            .toList();
                }
                for (Path file : adrFiles) {
                    String relPath = root.relativize(file).toString().replace('\\', '/');
                    String fileName = file.getFileName().toString();
                    String numPrefix = fileName.length() >= 4 ? fileName.substring(0, 4) : fileName;
                    String adrId = "ADR-" + numPrefix;

                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    String rawTitle = extractH1Title(lines, fileName);
                    String title = rawTitle.replaceFirst("^ADR-\\d+:\\s*", "").trim();
                    String status = extractBulletField(lines, "Status", "Accepted");
                    String dateText = extractBulletField(lines, "Datum", "");
                    String context = extractBulletField(lines, "Kontext", "");
                    String fullText = String.join("\n", lines);
                    String summary = extractSectionSummary(lines, "## Entscheidung", "## Problemstellung");
                    if (summary.isBlank()) {
                        summary = context.isBlank() ? extractLeadSummary(lines) : context;
                    }

                    EntityId entity = tx.createObject(s.adrType());
                    setStringAttr(tx, entity, s.predId(), s.codeIdVal(), adrId);
                    setStringAttr(tx, entity, s.predTitle(), s.titleTextVal(), title);
                    setStringAttr(tx, entity, s.predStatus(), s.statusTextVal(), cleanMarkdown(status));
                    if (!dateText.isBlank()) {
                        setStringAttr(tx, entity, s.predDateText(), s.dateTextVal(), cleanMarkdown(dateText));
                    }
                    setStringAttr(tx, entity, s.predFilePath(), s.filePathTextVal(), relPath);
                    setStringAttr(tx, entity, s.predCategory(), s.categoryNameVal(),
                            context.isBlank() ? "Architecture Decision" : cleanMarkdown(context));
                    setStringAttr(tx, entity, s.predSummary(), s.summaryTextVal(), summary);

                    EntityId docId = docByPath.get(relPath);
                    if (docId != null) {
                        tx.connect(entity, s.predDocumentedIn(), docId);
                    }
                    connectStageMentions(tx, entity, s.predBelongsToStage(), rawTitle + "\n" + status + "\n" + summary, stageByNumber);
                    linkInvariantMentions(tx, entity, s.predAddressesInvariant(), numPrefix, fullText, invariantById);

                    adrByNumber.put(numPrefix, entity);
                    adrCount++;
                }
            }

            Path logFile = root.resolve("docs/RESEARCH_LOG.md");
            if (Files.isRegularFile(logFile)) {
                EntityId logDoc = docByPath.get("docs/RESEARCH_LOG.md");
                List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                List<ResearchSection> sections = parseResearchSections(lines);
                for (ResearchSection sec : sections) {
                    EntityId entryId = tx.createObject(s.researchEntryType());
                    setStringAttr(tx, entryId, s.predId(), s.codeIdVal(), sec.code());
                    setStringAttr(tx, entryId, s.predTitle(), s.titleTextVal(), sec.title());
                    setStringAttr(tx, entryId, s.predCategory(), s.categoryNameVal(), "R&D Lab Entry");
                    if (!sec.dateText().isBlank()) {
                        setStringAttr(tx, entryId, s.predDateText(), s.dateTextVal(), sec.dateText());
                    }
                    setStringAttr(tx, entryId, s.predStatus(), s.statusTextVal(), sec.decision());
                    setStringAttr(tx, entryId, s.predSummary(), s.summaryTextVal(), sec.summary());

                    if (logDoc != null) {
                        tx.connect(entryId, s.predDocumentedIn(), logDoc);
                    }
                    connectStageMentions(tx, entryId, s.predBelongsToStage(), sec.title() + " " + sec.body(), stageByNumber);

                    Matcher adrMatcher = ADR_FILE_PATTERN.matcher(sec.body());
                    Set<String> seenAdrs = new LinkedHashSet<>();
                    while (adrMatcher.find()) {
                        seenAdrs.add(adrMatcher.group(1));
                    }
                    for (String adrNum : seenAdrs) {
                        EntityId adrEntity = adrByNumber.get(adrNum);
                        if (adrEntity != null) {
                            tx.connect(entryId, s.predRelatedAdr(), adrEntity);
                        }
                    }
                    researchCount++;
                }
            }
            tx.commit();
        }
        return new AdrAndResearchCounts(adrCount, researchCount);
    }

    private static List<Path> collectMarkdownDocuments(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        Path readme = root.resolve("README.md");
        if (Files.isRegularFile(readme)) {
            result.add(readme);
        }
        Path docsDir = root.resolve("docs");
        if (Files.isDirectory(docsDir)) {
            try (Stream<Path> walk = Files.walk(docsDir, 2)) {
                walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .forEach(result::add);
            }
        }
        return result;
    }

    private static String classifyDocument(String relPath) {
        if ("README.md".equals(relPath)) {
            return "Overview";
        }
        if (relPath.startsWith("docs/adr/")) {
            return "ADR";
        }
        if (relPath.startsWith("docs/experiments/")) {
            return "Experiment";
        }
        return "Core Doc";
    }

    private static String extractH1Title(List<String> lines, String fallback) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return cleanMarkdown(trimmed.substring(2));
            }
        }
        return fallback;
    }

    private static String extractLeadSummary(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("```") || t.startsWith("|")) {
                if (!sb.isEmpty()) {
                    break;
                }
                continue;
            }
            if (t.startsWith(">") || t.startsWith("-") || t.startsWith("*")) {
                t = t.replaceFirst("^[-*>]\\s*", "");
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(cleanMarkdown(t));
            if (sb.length() >= 220) {
                break;
            }
        }
        return truncate(sb.toString(), 260);
    }

    private static String extractBulletField(List<String> lines, String fieldName, String defaultValue) {
        String prefix = "- **" + fieldName + ":**";
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith(prefix)) {
                return cleanMarkdown(t.substring(prefix.length()));
            }
        }
        return defaultValue;
    }

    private static String extractSectionSummary(List<String> lines, String preferredHeading, String fallbackHeading) {
        String found = extractParagraphUnderHeading(lines, preferredHeading);
        if (!found.isBlank()) {
            return found;
        }
        return extractParagraphUnderHeading(lines, fallbackHeading);
    }

    private static String extractParagraphUnderHeading(List<String> lines, String headingPrefix) {
        boolean inSection = false;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith(headingPrefix)) {
                inSection = true;
                continue;
            }
            if (inSection) {
                if (t.startsWith("## ")) {
                    break;
                }
                if (t.isEmpty()) {
                    if (!sb.isEmpty() && sb.length() > 80) {
                        break;
                    }
                    continue;
                }
                if (t.startsWith("```")) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(cleanMarkdown(t.replaceFirst("^[-*]\\s+", "")));
                if (sb.length() >= 260) {
                    break;
                }
            }
        }
        return truncate(sb.toString(), 300);
    }

    private record ResearchSection(String code, String title, String dateText, String decision, String summary, String body) {
    }

    private static List<ResearchSection> parseResearchSections(List<String> lines) {
        Pattern headerPattern = Pattern.compile("^##\\s+(Eintrag\\s+(\\d+))\\s+[—-]\\s+(.+)$");
        List<ResearchSection> sections = new ArrayList<>();
        String curCode = null;
        String curTitle = null;
        List<String> curLines = new ArrayList<>();

        for (String line : lines) {
            Matcher m = headerPattern.matcher(line.trim());
            if (m.matches()) {
                if (curCode != null) {
                    sections.add(buildResearchSection(curCode, curTitle, curLines));
                }
                curCode = "Eintrag-" + String.format("%02d", Integer.parseInt(m.group(2)));
                curTitle = cleanMarkdown(m.group(3));
                curLines = new ArrayList<>();
            } else if (curCode != null) {
                curLines.add(line);
            }
        }
        if (curCode != null) {
            sections.add(buildResearchSection(curCode, curTitle, curLines));
        }
        return sections;
    }

    private static ResearchSection buildResearchSection(String code, String title, List<String> bodyLines) {
        String dateText = extractBulletField(bodyLines, "Datum", "");
        String hypothesis = extractSectionAfterBullet(bodyLines, "Stufe / Hypothese");
        String decision = extractSectionAfterBullet(bodyLines, "Entscheidung (weiter / anpassen / verwerfen)");
        String tests = extractSectionAfterBullet(bodyLines, "Tests (bestanden/fehlgeschlagen)");
        String combinedSummary = (!hypothesis.isBlank() ? hypothesis : (!tests.isBlank() ? tests : extractLeadSummary(bodyLines)));
        return new ResearchSection(
                code,
                title,
                cleanMarkdown(dateText),
                decision.isBlank() ? "Bestätigt" : cleanMarkdown(decision),
                truncate(cleanMarkdown(combinedSummary), 320),
                String.join("\n", bodyLines)
        );
    }

    private static String extractSectionAfterBullet(List<String> lines, String label) {
        String prefix = "- **" + label + ":**";
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith(prefix)) {
                String inline = t.substring(prefix.length()).trim();
                if (!inline.isEmpty()) {
                    return inline;
                }
                if (i + 1 < lines.size()) {
                    return lines.get(i + 1).trim();
                }
            }
        }
        return "";
    }

    private static void connectStageMentions(
            GraphTransaction tx,
            EntityId sourceId,
            EntityId stagePredId,
            String text,
            Map<Integer, EntityId> stageByNumber
    ) {
        Matcher m = STAGE_MENTION.matcher(text);
        Set<Integer> seen = new LinkedHashSet<>();
        while (m.find()) {
            try {
                seen.add(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        for (Integer stageNum : seen) {
            EntityId stageId = stageByNumber.get(stageNum);
            if (stageId != null) {
                tx.connect(sourceId, stagePredId, stageId);
            }
        }
    }

    private static void linkInvariantMentions(
            GraphTransaction tx,
            EntityId adrEntity,
            EntityId addressesPredId,
            String adrNumPrefix,
            String fullText,
            Map<String, EntityId> invariantById
    ) {
        Set<String> targets = new LinkedHashSet<>();
        Matcher m = INVARIANT_MENTION.matcher(fullText);
        while (m.find()) {
            targets.add(m.group(1));
        }
        // Fallback semantic mapping based on ADR number if explicit I-xx codes weren't written in text
        List<String> defaultInvariants = switch (adrNumPrefix) {
            case "0001" -> List.of("I-02", "I-10");
            case "0002" -> List.of("I-01", "I-04", "I-05", "I-08");
            case "0003" -> List.of("I-03", "I-09", "I-10");
            case "0004" -> List.of("I-11", "I-12", "I-13");
            case "0005" -> List.of("I-14", "I-15", "I-16");
            case "0006" -> List.of("I-17", "I-18", "I-19");
            case "0007" -> List.of("I-20", "I-21");
            case "0008" -> List.of("I-22");
            case "0009" -> List.of("I-23", "I-24");
            case "0010" -> List.of("I-25", "I-26", "I-27", "I-28");
            default -> List.of();
        };
        targets.addAll(defaultInvariants);
        for (String invCode : targets) {
            EntityId invId = invariantById.get(invCode);
            if (invId != null) {
                tx.connect(adrEntity, addressesPredId, invId);
            }
        }
    }

    private static String inferInvariantCategory(String code) {
        int num = Integer.parseInt(code.substring(2));
        if (num <= 10) {
            return "Semantic Core & Schema";
        }
        if (num <= 16) {
            return "Versioning, Branches & Merge";
        }
        if (num <= 21) {
            return "Security, Query & RDF";
        }
        if (num <= 24) {
            return "Storage, WAL & Recovery";
        }
        return "MVCC, Bitemporal & GDPR";
    }

    private static Integer inferInvariantStage(String code) {
        int num = Integer.parseInt(code.substring(2));
        if (num <= 8) {
            return 1;
        }
        if (num <= 10) {
            return 2;
        }
        if (num <= 13) {
            return 3;
        }
        if (num <= 16) {
            return 4;
        }
        if (num <= 19) {
            return 5;
        }
        if (num <= 21) {
            return 6;
        }
        if (num == 22) {
            return 7;
        }
        if (num <= 24) {
            return 8;
        }
        return 10;
    }

    private static List<String> splitMarkdownTableRow(String line) {
        String inner = line;
        if (inner.startsWith("|")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("|")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        String[] parts = inner.split("\\|", -1);
        List<String> cols = new ArrayList<>(parts.length);
        for (String part : parts) {
            cols.add(part.trim());
        }
        return cols;
    }

    private static String cleanMarkdown(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                .replace("**", "")
                .replace("`", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s == null ? "" : s;
        }
        return s.substring(0, maxLen - 3).trim() + "...";
    }

    private static void setStringAttr(
            GraphTransaction tx, EntityId objId, EntityId predId, EntityId valTypeId, String val
    ) {
        EntityId vId = tx.createValue(valTypeId, Value.of(val));
        tx.connect(objId, predId, vId);
    }

    private static void setIntAttr(
            GraphTransaction tx, EntityId objId, EntityId predId, EntityId valTypeId, long val
    ) {
        EntityId vId = tx.createValue(valTypeId, Value.of(val));
        tx.connect(objId, predId, vId);
    }
}
