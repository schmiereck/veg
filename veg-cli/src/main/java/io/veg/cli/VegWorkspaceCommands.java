package io.veg.cli;

import io.veg.cli.json.JsonCodec;
import io.veg.cli.kb.GraphTabularService;
import io.veg.cli.kb.GraphTabularService.SnapshotContext;
import io.veg.cli.kb.ProjectKnowledgeImporter;
import io.veg.cli.kb.ProjectKnowledgeImporter.ImportReport;
import io.veg.cli.mcp.VegMcpStdioServer;
import io.veg.cli.studio.VegStudioServer;
import io.veg.engine.VegPatchService.PatchApplyMode;
import io.veg.engine.persistent.PersistentDiskVersionedDatabase;
import io.veg.model.Actor;
import io.veg.model.EntityId;
import io.veg.model.IdGenerationStrategy;
import io.veg.model.Reason;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

/**
 * CLI handlers for workspace-level VEG database tools:
 * {@code import-project-docs}, {@code db}, {@code mcp}, and {@code studio}.
 */
final class VegWorkspaceCommands {

    private VegWorkspaceCommands() {
    }

    static int runImportProjectDocs(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path dbDir = Path.of("data/project-kb");
        Path docsRoot = Path.of(".");
        boolean clean = false;

        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if ("--clean".equals(flag)) {
                clean = true;
                continue;
            }
            if (i + 1 >= args.length) {
                err.println("Missing value for option: " + flag);
                return 1;
            }
            String val = args[++i];
            switch (flag) {
                case "--db" -> dbDir = Path.of(val);
                case "--docs-root" -> docsRoot = Path.of(val);
                default -> {
                    err.println("Unknown option: " + flag);
                    return 1;
                }
            }
        }

        if (clean && Files.exists(dbDir)) {
            deleteRecursively(dbDir);
        }
        Files.createDirectories(dbDir.toAbsolutePath().getParent());

        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            ImportReport report = ProjectKnowledgeImporter.importFromDocsRoot(db, docsRoot);
            out.printf(
                    "Imported project knowledge into %s | commits=%d | head=%s | tag=%s | docs=%d modules=%d stages=%d glossary=%d invariants=%d adrs=%d research=%d%n",
                    dbDir,
                    report.commitCount(),
                    report.headCommitId(),
                    report.tagName(),
                    report.documents(),
                    report.modules(),
                    report.stages(),
                    report.glossaryTerms(),
                    report.invariants(),
                    report.adrs(),
                    report.researchEntries()
            );
        }
        return 0;
    }

    static int runDb(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path dbDir = Path.of("data/project-kb");
        String subcommand = null;
        String typeName = null;
        String dsl = null;
        String search = null;
        String sortBy = "_id";
        String sortDir = "asc";
        int limit = 100;
        int offset = 0;
        String entityIdStr = null;
        String sourceIdStr = null;
        String targetIdStr = null;
        String edgeIdStr = null;
        String predicate = null;
        String value = null;
        boolean clear = false;
        String branch = null;
        String asOf = null;
        String validAt = null;
        String actorId = "cli-agent";
        String reasonText = null;
        boolean jsonOutput = false;
        String fromCommitStr = null;
        String toCommitStr = null;
        Path patchInputFile = null;
        Path patchOutputFile = null;
        PatchApplyMode patchMode = PatchApplyMode.MERGE;
        IdGenerationStrategy idStrategy = null;
        Map<String, String> createAttrs = new LinkedHashMap<>();

        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--") && subcommand == null) {
                subcommand = token;
                continue;
            }
            if ("--json".equals(token)) {
                jsonOutput = true;
                continue;
            }
            if ("--clear".equals(token)) {
                clear = true;
                continue;
            }
            if (i + 1 >= args.length) {
                err.println("Missing value for option: " + token);
                return 1;
            }
            String val = args[++i];
            switch (token) {
                case "--db" -> dbDir = Path.of(val);
                case "--type" -> typeName = val;
                case "--dsl" -> dsl = val;
                case "--search" -> search = val;
                case "--sort-by" -> sortBy = val;
                case "--sort-dir" -> sortDir = val;
                case "--limit" -> limit = Integer.parseInt(val);
                case "--offset" -> offset = Integer.parseInt(val);
                case "--id" -> entityIdStr = val;
                case "--source" -> sourceIdStr = val;
                case "--target" -> targetIdStr = val;
                case "--edge" -> edgeIdStr = val;
                case "--pred" -> predicate = val;
                case "--value" -> value = val;
                case "--branch" -> branch = val;
                case "--as-of" -> asOf = val;
                case "--valid-at" -> validAt = val;
                case "--actor" -> actorId = val;
                case "--reason" -> reasonText = val;
                case "--from" -> fromCommitStr = val;
                case "--to" -> toCommitStr = val;
                case "--input" -> patchInputFile = Path.of(val);
                case "--output" -> patchOutputFile = Path.of(val);
                case "--mode" -> patchMode = PatchApplyMode.valueOf(val.trim().toUpperCase(Locale.ROOT));
                case "--id-strategy" -> idStrategy = parseIdStrategy(val);
                case "--attr" -> {
                    int eq = val.indexOf('=');
                    if (eq <= 0) {
                        err.println("Expected --attr key=value, got: " + val);
                        return 1;
                    }
                    createAttrs.put(val.substring(0, eq), val.substring(eq + 1));
                }
                default -> {
                    err.println("Unknown option: " + token);
                    return 1;
                }
            }
        }

        if (subcommand == null) {
            err.println("Expected db subcommand: schema | datasheet | query | inspect | commits | set-attr | create-object | connect | disconnect | export-patch | apply-patch");
            return 1;
        }
        if (!Files.exists(dbDir)) {
            err.println("Database directory does not exist: " + dbDir);
            return 1;
        }

        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            if (idStrategy != null) {
                db.setIdGenerationStrategy(idStrategy);
            }
            SnapshotContext ctx = GraphTabularService.resolveContext(db, branch, asOf, validAt);
            Actor actor = Actor.of(actorId, actorId);
            String defaultReason = "CLI db " + subcommand;
            Reason reason = Reason.of(reasonText != null && !reasonText.isBlank() ? reasonText : defaultReason);

            Object payload = switch (subcommand) {
                case "schema" -> GraphTabularService.overview(db, ctx);
                case "datasheet" -> {
                    if (typeName == null) {
                        throw new IllegalArgumentException("Missing required option --type");
                    }
                    Map<String, String> filters = new LinkedHashMap<>();
                    if (search != null && !search.isBlank()) {
                        filters.put("_q", search);
                    }
                    yield GraphTabularService.datasheet(ctx, typeName, filters, sortBy, sortDir, limit, offset);
                }
                case "query" -> {
                    if (dsl == null) {
                        throw new IllegalArgumentException("Missing required option --dsl");
                    }
                    yield GraphTabularService.executeDslQuery(ctx, dsl);
                }
                case "inspect" -> {
                    if (entityIdStr == null) {
                        throw new IllegalArgumentException("Missing required option --id");
                    }
                    yield GraphTabularService.entityDetail(db, ctx, GraphTabularService.parseEntityId(entityIdStr));
                }
                case "commits" -> GraphTabularService.commitsHistory(db);
                case "set-attr" -> {
                    if (entityIdStr == null || predicate == null) {
                        throw new IllegalArgumentException("Missing required options --id and --pred");
                    }
                    yield GraphTabularService.setAttributeCell(
                            db, ctx.branchName(), actor, reason,
                            GraphTabularService.parseEntityId(entityIdStr), predicate, value, clear
                    );
                }
                case "create-object" -> {
                    if (typeName == null) {
                        throw new IllegalArgumentException("Missing required option --type");
                    }
                    yield GraphTabularService.createObject(
                            db, ctx.branchName(), actor, reason, typeName, createAttrs, idStrategy
                    );
                }
                case "connect" -> {
                    if (sourceIdStr == null || predicate == null || targetIdStr == null) {
                        throw new IllegalArgumentException("Missing required options --source, --pred, --target");
                    }
                    yield GraphTabularService.connectEntities(
                            db, ctx.branchName(), actor, reason,
                            GraphTabularService.parseEntityId(sourceIdStr),
                            predicate,
                            GraphTabularService.parseEntityId(targetIdStr)
                    );
                }
                case "disconnect" -> {
                    if (edgeIdStr == null) {
                        throw new IllegalArgumentException("Missing required option --edge");
                    }
                    yield GraphTabularService.disconnectEdge(
                            db, ctx.branchName(), actor, reason,
                            GraphTabularService.parseEntityId(edgeIdStr)
                    );
                }
                case "export-patch" -> {
                    if (fromCommitStr == null || toCommitStr == null) {
                        throw new IllegalArgumentException("Missing required options --from and --to");
                    }
                    Map<String, Object> exported = GraphTabularService.exportPatch(db, fromCommitStr, toCommitStr);
                    if (patchOutputFile != null) {
                        if (patchOutputFile.toAbsolutePath().getParent() != null) {
                            Files.createDirectories(patchOutputFile.toAbsolutePath().getParent());
                        }
                        Files.writeString(patchOutputFile, String.valueOf(exported.get("patchText")), StandardCharsets.UTF_8);
                    }
                    yield exported;
                }
                case "apply-patch" -> {
                    if (patchInputFile == null) {
                        throw new IllegalArgumentException("Missing required option --input");
                    }
                    String patchText = Files.readString(patchInputFile, StandardCharsets.UTF_8);
                    Actor patchActor = actorId.equals("cli-agent") && reasonText == null ? null : actor;
                    Reason patchReason = reasonText == null ? null : reason;
                    yield GraphTabularService.applyOrMergePatch(
                            db, patchText, ctx.branchName(), patchMode, patchActor, patchReason
                    );
                }
                default -> throw new IllegalArgumentException("Unknown db subcommand: " + subcommand);
            };

            if ("export-patch".equals(subcommand) && !jsonOutput) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) payload;
                out.print(m.get("patchText"));
            } else if (jsonOutput) {
                out.println(JsonCodec.toJson(payload));
            } else {
                printReadableDbOutput(subcommand, payload, out);
            }
            return 0;
        } catch (RuntimeException e) {
            err.println("db error: " + e.getMessage());
            return 1;
        }
    }

    static int runMcp(String[] args, InputStream in, PrintStream out, PrintStream err) throws IOException {
        Path dbDir = Path.of("data/project-kb");
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if ("--db".equals(flag) && i + 1 < args.length) {
                dbDir = Path.of(args[++i]);
            } else {
                err.println("Unknown or incomplete option: " + flag);
                return 1;
            }
        }
        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            new VegMcpStdioServer(db).runLoop(in, out, err);
        }
        return 0;
    }

    static int runStudio(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path dbDir = Path.of("data/project-kb");
        int port = 8080;
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                err.println("Missing value for option: " + flag);
                return 1;
            }
            String val = args[++i];
            switch (flag) {
                case "--db" -> dbDir = Path.of(val);
                case "--port" -> port = Integer.parseInt(val);
                default -> {
                    err.println("Unknown option: " + flag);
                    return 1;
                }
            }
        }
        if (!Files.exists(dbDir)) {
            err.println("Database directory does not exist: " + dbDir + " (run import-project-docs first)");
            return 1;
        }

        PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir);
        VegStudioServer server = new VegStudioServer(db, port);
        CountDownLatch stopLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            db.close();
            stopLatch.countDown();
        }, "veg-studio-shutdown"));

        server.start();
        out.printf("VEG Access Studio running at %s (db=%s)%n", server.baseUrl(), dbDir.toAbsolutePath());
        out.flush();
        try {
            stopLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.close();
            db.close();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static void printReadableDbOutput(String subcommand, Object payload, PrintStream out) {
        Map<String, Object> m = (Map<String, Object>) payload;
        switch (subcommand) {
            case "schema" -> {
                out.printf("Branch=%s | Commit=%s | Entities=%s | Edges=%s%n",
                        m.get("activeBranch"), m.get("commitId"), m.get("entityCount"), m.get("edgeCount"));
                out.println("ObjectTypes:");
                for (Map<String, Object> ot : (List<Map<String, Object>>) m.get("objectTypes")) {
                    out.printf("  - %s (#%s): %s rows%n", ot.get("name"), ot.get("id"), ot.get("count"));
                }
                out.println("Predicates:");
                for (Map<String, Object> p : (List<Map<String, Object>>) m.get("predicates")) {
                    out.printf("  - %s (#%s): %s -> %s (%s edges)%n",
                            p.get("name"), p.get("id"), p.get("domainName"), p.get("rangeName"), p.get("count"));
                }
            }
            case "datasheet" -> {
                out.printf("Datasheet %s (%s/%s rows) | Branch=%s Commit=%s%n",
                        m.get("typeName"), m.get("filteredCount"), m.get("totalCount"), m.get("branch"), m.get("commitId"));
                for (Map<String, Object> row : (List<Map<String, Object>>) m.get("rows")) {
                    out.printf("  #%s %s | %s%n", row.get("_id"), row.get("_label"), row.get("_cells"));
                }
            }
            case "query" -> {
                out.printf("Query rows=%s | Branch=%s Commit=%s%n",
                        m.get("rowCount"), m.get("branch"), m.get("commitId"));
                for (Map<String, Object> row : (List<Map<String, Object>>) m.get("rows")) {
                    out.printf("  bindings=%s values=%s%n", row.get("bindings"), row.get("values"));
                }
            }
            default -> out.println(JsonCodec.toJson(payload));
        }
    }

    private static IdGenerationStrategy parseIdStrategy(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "sequential", "seq" -> IdGenerationStrategy.SEQUENTIAL;
            case "uuid-v7", "uuid_v7", "uuidv7", "uuid" -> IdGenerationStrategy.UUID_V7;
            case "uuid-v4", "uuid_v4", "uuidv4" -> IdGenerationStrategy.UUID_V4;
            default -> throw new IllegalArgumentException("Unknown id strategy: " + raw);
        };
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
