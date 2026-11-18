package io.veg.cli;

import io.veg.cli.desktop.VegServerController;
import io.veg.cli.desktop.VegServerController.ServerStatus;
import io.veg.cli.desktop.WindowsIconGenerator;
import io.veg.cli.json.JsonCodec;
import io.veg.cli.kb.ProjectKnowledgeImporter;
import io.veg.cli.kb.ProjectKnowledgeImporter.ImportReport;
import io.veg.cli.mcp.VegMcpStdioServer;
import io.veg.cli.studio.VegRemoteServerClient;
import io.veg.cli.studio.VegStudioServer;
import io.veg.engine.VegPatchService.PatchApplyMode;
import io.veg.engine.VegPatchService.PatchResult;
import io.veg.engine.persistent.PersistentDiskVersionedDatabase;
import io.veg.model.IdGenerationStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectKnowledgeAndStudioTest {

    private static Path resolveRepoRoot() {
        Path direct = Path.of("docs/INVARIANTS.md");
        if (Files.isRegularFile(direct)) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        Path parent = Path.of("../docs/INVARIANTS.md");
        if (Files.isRegularFile(parent)) {
            return Path.of("..").toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Could not locate repository root from " + Path.of("").toAbsolutePath());
    }

    @Test
    void importerAndCliDbCommandsWorkAgainstProjectMarkdownDocs(@TempDir Path tempDir) throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path dbDir = tempDir.resolve("kb");

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

        int importCode = VegCliMain.execute(
                new String[]{"import-project-docs", "--db", dbDir.toString(), "--docs-root", repoRoot.toString()},
                out, err
        );
        assertThat(importCode).isZero();
        assertThat(errBuf.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(outBuf.toString(StandardCharsets.UTF_8))
                .contains("commits=5", "head=c5", "tag=v1.0-stage10", "invariants=28", "adrs=10", "stages=11", "modules=7");

        // Verify schema JSON output via `db schema --json`
        outBuf.reset();
        int schemaCode = VegCliMain.execute(
                new String[]{"db", "--db", dbDir.toString(), "schema", "--json"},
                out, err
        );
        assertThat(schemaCode).isZero();
        Map<String, Object> schema = JsonCodec.parseObject(outBuf.toString(StandardCharsets.UTF_8));
        assertThat(schema.get("headCommitId")).isEqualTo("c5");
        assertThat(JsonCodec.asArray(schema.get("objectTypes"))).hasSize(7);

        // Verify DSL query via `db query` across ADRs and Invariants
        outBuf.reset();
        int queryCode = VegCliMain.execute(
                new String[]{
                        "db", "--db", dbDir.toString(), "query",
                        "--dsl", "MATCH (adr:ArchitectureDecision) JOIN (adr)-[addressesInvariant]->(inv:Invariant) WHERE inv.id = \"I-17\" SELECT adr.id, adr.title, inv.id",
                        "--json"
                },
                out, err
        );
        assertThat(queryCode).isZero();
        Map<String, Object> queryRes = JsonCodec.parseObject(outBuf.toString(StandardCharsets.UTF_8));
        assertThat(((Number) queryRes.get("rowCount")).intValue()).isGreaterThanOrEqualTo(1);
        List<Object> rows = JsonCodec.asArray(queryRes.get("rows"));
        Map<String, Object> firstValues = JsonCodec.asObject(JsonCodec.asObject(rows.getFirst()).get("values"));
        assertThat(firstValues.get("adr.id")).isEqualTo("ADR-0006");

        // Verify `db create-object` + `db set-attr` persist to disk commit c6 & c7
        outBuf.reset();
        int createCode = VegCliMain.execute(
                new String[]{
                        "db", "--db", dbDir.toString(), "create-object",
                        "--type", "Invariant",
                        "--attr", "id=I-99",
                        "--attr", "title=Agent Test Invariant",
                        "--actor", "test-agent",
                        "--reason", "Verify CLI create-object commit",
                        "--json"
                },
                out, err
        );
        assertThat(createCode).isZero();
        Map<String, Object> created = JsonCodec.parseObject(outBuf.toString(StandardCharsets.UTF_8));
        String createdId = String.valueOf(created.get("entityId"));
        assertThat(created.get("commitId")).isEqualTo("c6");

        outBuf.reset();
        int editCode = VegCliMain.execute(
                new String[]{
                        "db", "--db", dbDir.toString(), "set-attr",
                        "--id", createdId,
                        "--pred", "status",
                        "--value", "Verified",
                        "--actor", "test-agent",
                        "--reason", "Verify CLI set-attr",
                        "--json"
                },
                out, err
        );
        assertThat(editCode).isZero();
        assertThat(outBuf.toString(StandardCharsets.UTF_8)).contains("\"commitId\":\"c7\"");

        // Create UUIDv7 entity, export c7 -> c8 as .vegpatch file, then apply into a clean clone
        outBuf.reset();
        int uuidCreateCode = VegCliMain.execute(
                new String[]{
                        "db", "--db", dbDir.toString(), "create-object",
                        "--id-strategy", "uuid-v7",
                        "--type", "Invariant",
                        "--attr", "id=I-100",
                        "--attr", "title=CLI UUID Invariant",
                        "--actor", "test-agent",
                        "--reason", "Create UUIDv7 entity",
                        "--json"
                },
                out, err
        );
        assertThat(uuidCreateCode).isZero();
        Map<String, Object> uuidCreated = JsonCodec.parseObject(outBuf.toString(StandardCharsets.UTF_8));
        String uuidEntityId = String.valueOf(uuidCreated.get("entityId"));
        assertThat(uuidEntityId).contains("-");

        Path patchFile = tempDir.resolve("pr.vegpatch");
        outBuf.reset();
        int exportPatchCode = VegCliMain.execute(
                new String[]{
                        "db", "--db", dbDir.toString(), "export-patch",
                        "--from", "c7", "--to", "c8", "--output", patchFile.toString()
                },
                out, err
        );
        assertThat(exportPatchCode).isZero();
        assertThat(Files.readString(patchFile, StandardCharsets.UTF_8))
                .contains("VEG-PATCH v1", uuidEntityId, "CLI UUID Invariant");

        Path dbClone = tempDir.resolve("kb-clone");
        assertThat(VegCliMain.execute(
                new String[]{"import-project-docs", "--db", dbClone.toString(), "--docs-root", repoRoot.toString()},
                out, err
        )).isZero();
        outBuf.reset();
        int applyPatchCode = VegCliMain.execute(
                new String[]{
                        "db", "--db", dbClone.toString(), "apply-patch",
                        "--input", patchFile.toString(), "--mode", "apply", "--json"
                },
                out, err
        );
        assertThat(applyPatchCode).isZero();
        Map<String, Object> applyRes = JsonCodec.parseObject(outBuf.toString(StandardCharsets.UTF_8));
        assertThat(applyRes.get("merged")).isEqualTo(true);

        outBuf.reset();
        assertThat(VegCliMain.execute(
                new String[]{"db", "--db", dbClone.toString(), "inspect", "--id", uuidEntityId, "--json"},
                out, err
        )).isZero();
        assertThat(outBuf.toString(StandardCharsets.UTF_8)).contains("CLI UUID Invariant");
    }

    @Test
    void mcpStdioServerHandlesInitializeListQueryAndMutation(@TempDir Path tempDir) throws Exception {
        Path dbDir = tempDir.resolve("mcp-db");
        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir)) {
            ImportReport report = ProjectKnowledgeImporter.importFromDocsRoot(db, resolveRepoRoot());
            assertThat(report.invariants()).isEqualTo(28);
            assertThat(report.glossaryTerms()).isGreaterThanOrEqualTo(10);
            assertThat(report.researchEntries()).isGreaterThanOrEqualTo(7);

            VegMcpStdioServer mcp = new VegMcpStdioServer(db);
            String requests = String.join("\n",
                    JsonCodec.toJson(Map.of(
                            "jsonrpc", "2.0", "id", 1, "method", "initialize",
                            "params", Map.of("protocolVersion", "2024-11-05")
                    )),
                    JsonCodec.toJson(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")),
                    JsonCodec.toJson(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list")),
                    JsonCodec.toJson(Map.of(
                            "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                            "params", Map.of("name", "veg_schema", "arguments", Map.of())
                    )),
                    JsonCodec.toJson(Map.of(
                            "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                            "params", Map.of("name", "veg_datasheet", "arguments", Map.of("typeName", "Invariant", "search", "WAL"))
                    )),
                    JsonCodec.toJson(Map.of(
                            "jsonrpc", "2.0", "id", 5, "method", "tools/call",
                            "params", Map.of("name", "veg_query", "arguments", Map.of(
                                    "dsl", "MATCH (m:Module) JOIN (m)-[dependsOnModule]->(dep:Module) WHERE m.id = \"veg-engine-persistent\" SELECT m.id, dep.id"
                            ))
                    )),
                    JsonCodec.toJson(Map.of(
                            "jsonrpc", "2.0", "id", 6, "method", "tools/call",
                            "params", Map.of("name", "veg_mutate", "arguments", Map.of(
                                    "action", "create_object",
                                    "typeName", "GlossaryTerm",
                                    "actor", "mcp-coding-agent",
                                    "reason", "Add MCP concept term",
                                    "attributes", Map.of("id", "MCP", "title", "Model Context Protocol", "summary", "JSON-RPC stdio tool interface")
                            ))
                    )),
                    JsonCodec.toJson(Map.of(
                            "jsonrpc", "2.0", "id", 7, "method", "tools/call",
                            "params", Map.of("name", "veg_datasheet", "arguments", Map.of("typeName", "GlossaryTerm", "search", "MCP"))
                    ))
            );

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            mcp.processStream(
                    new BufferedReader(new StringReader(requests)),
                    new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
            );

            List<String> lines = outBuf.toString(StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank())
                    .toList();
            // notification produces no line -> 7 JSON-RPC responses
            assertThat(lines).hasSize(7);

            Map<String, Object> initRes = JsonCodec.asObject(JsonCodec.parseObject(lines.get(0)).get("result"));
            assertThat(JsonCodec.asObject(initRes.get("serverInfo")).get("name")).isEqualTo("veg-mcp");

            List<Object> tools = JsonCodec.asArray(JsonCodec.asObject(JsonCodec.parseObject(lines.get(1)).get("result")).get("tools"));
            assertThat(tools).hasSize(7);

            Map<String, Object> dsPayload = parseToolTextPayload(lines.get(3));
            assertThat(((Number) dsPayload.get("filteredCount")).intValue()).isGreaterThanOrEqualTo(2);

            Map<String, Object> queryPayload = parseToolTextPayload(lines.get(4));
            assertThat(((Number) queryPayload.get("rowCount")).intValue()).isEqualTo(2);

            Map<String, Object> mutatePayload = parseToolTextPayload(lines.get(5));
            assertThat(mutatePayload.get("commitId")).isEqualTo("c6");
            String createdEntityId = String.valueOf(mutatePayload.get("entityId"));

            Map<String, Object> glossarySearch = parseToolTextPayload(lines.get(6));
            assertThat(((Number) glossarySearch.get("filteredCount")).intValue()).isEqualTo(1);

            // Inspect newly created entity via veg_entity
            String inspectResp = mcp.handleJsonRpcLine(JsonCodec.toJson(Map.of(
                    "jsonrpc", "2.0", "id", 8, "method", "tools/call",
                    "params", Map.of("name", "veg_entity", "arguments", Map.of("entityId", createdEntityId))
            )), null);
            Map<String, Object> entityPayload = parseToolTextPayload(inspectResp);
            assertThat(entityPayload.get("typeName")).isEqualTo("GlossaryTerm");
            assertThat(JsonCodec.asArray(entityPayload.get("attributes"))).hasSize(3);

            // Export c5 -> c6 as .vegpatch via veg_patch
            String patchExportResp = mcp.handleJsonRpcLine(JsonCodec.toJson(Map.of(
                    "jsonrpc", "2.0", "id", 9, "method", "tools/call",
                    "params", Map.of("name", "veg_patch", "arguments", Map.of(
                            "action", "export", "fromCommit", "c5", "toCommit", "c6"
                    ))
            )), null);
            Map<String, Object> exportedPatch = parseToolTextPayload(patchExportResp);
            assertThat(String.valueOf(exportedPatch.get("patchText")))
                    .contains("VEG-PATCH v1", "Model Context Protocol");
        }
    }

    @Test
    void studioServerServesUiSchemaDatasheetEditQueryAndTimeTravel(@TempDir Path tempDir) throws Exception {
        Path dbDir = tempDir.resolve("studio-db");
        try (PersistentDiskVersionedDatabase db = PersistentDiskVersionedDatabase.open(dbDir);
             VegStudioServer server = new VegStudioServer(db, 0)) {

            ProjectKnowledgeImporter.importFromDocsRoot(db, resolveRepoRoot());
            server.start();

            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + server.port());

            HttpResponse<String> htmlResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/studio/index.html")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(htmlResp.statusCode()).isEqualTo(200);
            assertThat(htmlResp.body()).contains("VEG Access Studio", "Objektkatalog");

            HttpResponse<String> ovResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/overview")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(ovResp.statusCode()).isEqualTo(200);
            Map<String, Object> overview = JsonCodec.parseObject(ovResp.body());
            assertThat(overview.get("headCommitId")).isEqualTo("c5");

            // Filtered & sorted datasheet for Invariant matching WAL
            HttpResponse<String> dsResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/datasheet?type=Invariant&q=WAL&sortBy=id&sortDir=asc")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(dsResp.statusCode()).isEqualTo(200);
            Map<String, Object> ds = JsonCodec.parseObject(dsResp.body());
            List<Object> rows = JsonCodec.asArray(ds.get("rows"));
            assertThat(rows.size()).isGreaterThanOrEqualTo(2);
            Map<String, Object> firstRow = JsonCodec.asObject(rows.getFirst());
            String invEntityId = String.valueOf(firstRow.get("_id"));

            // Edit cell inline via POST /api/cell
            HttpResponse<String> cellResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/cell"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.toJson(Map.of(
                                    "entityId", invEntityId,
                                    "predicate", "status",
                                    "value", "Studio Verified",
                                    "actor", "studio-tester",
                                    "reason", "Edit status in test"
                            )), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(cellResp.statusCode()).isEqualTo(200);
            assertThat(JsonCodec.parseObject(cellResp.body()).get("commitId")).isEqualTo("c6");

            // Execute DSL query via POST /api/query
            HttpResponse<String> queryResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/query"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.toJson(Map.of(
                                    "dsl", "MATCH (m:Module) JOIN (m)-[implementsStage]->(s:Stage) WHERE m.id = \"veg-engine-persistent\" SELECT m.id, s.id"
                            )), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(queryResp.statusCode()).isEqualTo(200);
            Map<String, Object> queryRes = JsonCodec.parseObject(queryResp.body());
            assertThat(((Number) queryRes.get("rowCount")).intValue()).isEqualTo(2);

            // Inspect entity detail, connect a relation, then disconnect it
            HttpResponse<String> docResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/datasheet?type=Document&limit=1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(docResp.statusCode()).isEqualTo(200);
            String docId = String.valueOf(JsonCodec.asObject(
                    JsonCodec.asArray(JsonCodec.parseObject(docResp.body()).get("rows")).getFirst()
            ).get("_id"));

            HttpResponse<String> connResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/connect"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.toJson(Map.of(
                                    "sourceId", invEntityId,
                                    "predicate", "documentedIn",
                                    "targetId", docId,
                                    "actor", "studio-tester",
                                    "reason", "Link extra doc"
                            )), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(connResp.statusCode()).isEqualTo(200);
            String edgeId = String.valueOf(JsonCodec.parseObject(connResp.body()).get("edgeId"));

            HttpResponse<String> entityResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/entity?id=" + invEntityId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(entityResp.statusCode()).isEqualTo(200);
            Map<String, Object> detail = JsonCodec.parseObject(entityResp.body());
            assertThat(JsonCodec.asArray(detail.get("outgoingRelations")).size()).isGreaterThanOrEqualTo(2);

            HttpResponse<String> discResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/disconnect"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.toJson(Map.of(
                                    "edgeId", edgeId,
                                    "actor", "studio-tester",
                                    "reason", "Remove extra doc link"
                            )), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(discResp.statusCode()).isEqualTo(200);

            // Create new object + verify historical snapshot c3 does not see invariants/c6 edits
            HttpResponse<String> createObjResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/objects"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.toJson(Map.of(
                                    "typeName", "Invariant",
                                    "actor", "studio-tester",
                                    "reason", "Create via Studio",
                                    "attributes", Map.of("id", "I-90", "title", "Live Studio Row")
                            )), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(createObjResp.statusCode()).isEqualTo(200);

            HttpResponse<String> histResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/datasheet?type=Invariant&asOf=c3")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(histResp.statusCode()).isEqualTo(200);
            Map<String, Object> histDs = JsonCodec.parseObject(histResp.body());
            assertThat(histDs.get("historical")).isEqualTo(true);
            assertThat(((Number) histDs.get("totalCount")).intValue()).isZero();

            // Diff between c5 and c6 captures the status update
            HttpResponse<String> diffResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/diff?from=c5&to=c6")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(diffResp.statusCode()).isEqualTo(200);
            Map<String, Object> diff = JsonCodec.parseObject(diffResp.body());
            assertThat(diff.get("empty")).isEqualTo(false);

            // Studio GET /api/patch exports text diff for Git/PR workflow
            HttpResponse<String> patchGetResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/patch?from=c5&to=c6")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(patchGetResp.statusCode()).isEqualTo(200);
            String patchText = String.valueOf(JsonCodec.parseObject(patchGetResp.body()).get("patchText"));
            assertThat(patchText).contains("VEG-PATCH v1", "Studio Verified");

            // Studio POST /api/patch merges an exported UUID patch into the DB
            db.setIdGenerationStrategy(io.veg.model.IdGenerationStrategy.UUID_V7);
            io.veg.model.CommitId preUuid = db.headCommitId();
            db.createBranch("temp/pr", preUuid);
            io.veg.model.CommitId prCommit;
            try (io.veg.engine.GraphTransaction tx = db.beginTransaction(
                    "temp/pr", io.veg.model.Actor.user("pr-bot"), io.veg.model.Reason.of("PR via UUID"))) {
                io.veg.model.EntityId invType = tx.findTypeByName("Invariant").orElseThrow();
                io.veg.model.EntityId prObj = tx.createObject(invType);
                tx.connect(prObj, tx.findPredicateByName("id").orElseThrow(),
                        tx.createValue(tx.findTypeByName("CodeId").orElseThrow(), io.veg.model.Value.of("I-95")));
                tx.connect(prObj, tx.findPredicateByName("title").orElseThrow(),
                        tx.createValue(tx.findTypeByName("TitleText").orElseThrow(), io.veg.model.Value.of("UUID PR Invariant")));
                prCommit = tx.commit();
            }
            String uuidPatch = io.veg.engine.VegPatchService.exportPatch(db, preUuid, prCommit);

            HttpResponse<String> patchPostResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/patch"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.toJson(Map.of(
                                    "patchText", uuidPatch,
                                    "mode", "MERGE",
                                    "branch", "main"
                            )), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(patchPostResp.statusCode()).isEqualTo(200);
            Map<String, Object> patchRes = JsonCodec.parseObject(patchPostResp.body());
            assertThat(patchRes.get("merged")).isEqualTo(true);
            assertThat(db.currentSnapshot().executeQuery("MATCH (i:Invariant) WHERE i.id = \"I-95\" SELECT i.title"))
                    .hasSize(1);

            HttpResponse<byte[]> logoResp = client.send(
                    HttpRequest.newBuilder(base.resolve("/studio/logo.png")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            assertThat(logoResp.statusCode()).isEqualTo(200);
            assertThat(logoResp.headers().firstValue("Content-Type")).contains("image/png");
            assertThat(logoResp.body().length).isGreaterThan(100);
            assertThat(logoResp.body()[0]).isEqualTo((byte) 0x89);
            assertThat(logoResp.body()[1]).isEqualTo((byte) 'P');
        }
    }

    @Test
    void desktopServerControllerAndIconGeneratorWorkCleanly(@TempDir Path tempDir) throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path logoJpg = repoRoot.resolve("pic/logo-veg-smal.jpg");
        BufferedImage rendered = WindowsIconGenerator.renderSquareIcon(logoJpg, 256);
        assertThat(rendered.getWidth()).isEqualTo(256);
        assertThat(rendered.getHeight()).isEqualTo(256);

        Path pngOut = tempDir.resolve("logo.png");
        Path icoOut = tempDir.resolve("VEG-Studio.ico");
        WindowsIconGenerator.writePng(rendered, pngOut);
        WindowsIconGenerator.writeIco(rendered, icoOut);

        byte[] pngBytes = Files.readAllBytes(pngOut);
        assertThat(pngBytes[0]).isEqualTo((byte) 0x89);
        assertThat(pngBytes[1]).isEqualTo((byte) 'P');
        assertThat(ImageIO.read(pngOut.toFile()).getWidth()).isEqualTo(256);

        byte[] icoBytes = Files.readAllBytes(icoOut);
        assertThat(icoBytes.length).isGreaterThan(22);
        // ICO header: reserved=0x0000, type=0x0001, count=0x0001
        assertThat(icoBytes[0]).isEqualTo((byte) 0x00);
        assertThat(icoBytes[1]).isEqualTo((byte) 0x00);
        assertThat(icoBytes[2]).isEqualTo((byte) 0x01);
        assertThat(icoBytes[3]).isEqualTo((byte) 0x00);
        assertThat(icoBytes[4]).isEqualTo((byte) 0x01);
        assertThat(icoBytes[5]).isEqualTo((byte) 0x00);

        Path dbMain = tempDir.resolve("db-main");
        Path dbTarget = tempDir.resolve("db-target");
        Path backupDir = tempDir.resolve("backup");
        Path restoredDir = tempDir.resolve("db-restored");
        Path patchFile = tempDir.resolve("kb.vegpatch");

        try (VegServerController controller = new VegServerController(dbMain, 0)) {
            assertThat(controller.status().running()).isFalse();
            controller.startServer();

            ServerStatus initialStatus = controller.status();
            assertThat(initialStatus.running()).isTrue();
            assertThat(initialStatus.activePort()).isGreaterThan(0);
            assertThat(initialStatus.studioUrl()).startsWith("http://127.0.0.1:");
            String baseCommit = initialStatus.headCommitId();
            long baselineEntities = initialStatus.entityCount();

            controller.setIdStrategy(IdGenerationStrategy.UUID_V7);
            assertThat(controller.status().idStrategy()).isEqualTo(IdGenerationStrategy.UUID_V7);

            ImportReport report = controller.importProjectDocs(repoRoot, false);
            assertThat(report.invariants()).isGreaterThan(20);

            ServerStatus afterImport = controller.status();
            assertThat(afterImport.entityCount()).isGreaterThan(50);
            assertThat(afterImport.commitCount()).isGreaterThanOrEqualTo(2);

            String patchText = controller.exportVegPatch(baseCommit, afterImport.headCommitId(), patchFile);
            assertThat(patchText).contains("VEG-PATCH v1");
            assertThat(Files.isRegularFile(patchFile)).isTrue();

            controller.createCheckpoint();
            controller.createBackup(backupDir);

            controller.createFreshDatabase(dbTarget);
            assertThat(controller.status().running()).isTrue();
            assertThat(controller.status().entityCount()).isEqualTo(baselineEntities);

            PatchResult applied = controller.applyVegPatch(patchFile, PatchApplyMode.MERGE);
            assertThat(applied.merged()).isTrue();
            assertThat(controller.status().entityCount()).isEqualTo(afterImport.entityCount());

            controller.restoreBackup(backupDir, restoredDir);
            assertThat(controller.status().running()).isTrue();
            assertThat(controller.status().dbDir()).isEqualTo(restoredDir.toAbsolutePath().normalize());
            assertThat(controller.status().entityCount()).isEqualTo(afterImport.entityCount());

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> uiResp = client.send(
                    HttpRequest.newBuilder(URI.create(controller.status().studioUrl())).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(uiResp.statusCode()).isEqualTo(200);
            assertThat(uiResp.body()).contains("/studio/logo.png");

            controller.stopServer();
            assertThat(controller.status().running()).isFalse();
            assertThat(controller.recentLogs()).isNotEmpty();
        }
    }

    @Test
    void cliAndMcpConnectAutomaticallyToRunningServer(@TempDir Path tempDir) throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path liveDb = tempDir.resolve("live-db");
        Path activeMarker = liveDb.resolve(VegRemoteServerClient.ACTIVE_SERVER_FILE);

        try (VegServerController controller = new VegServerController(liveDb, 0)) {
            controller.startServer();
            assertThat(Files.isRegularFile(activeMarker)).isTrue();

            ImportReport imported = controller.importProjectDocs(repoRoot, false);
            assertThat(imported.invariants()).isGreaterThan(20);
            long entitiesBeforeRemote = controller.status().entityCount();
            int commitsBeforeRemote = controller.status().commitCount();

            // 1. CLI `db` commands transparently route through the running HTTP server (`server.active`)
            ByteArrayOutputStream cliOutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream cliErrBuf = new ByteArrayOutputStream();
            PrintStream cliOut = new PrintStream(cliOutBuf, true, StandardCharsets.UTF_8);
            PrintStream cliErr = new PrintStream(cliErrBuf, true, StandardCharsets.UTF_8);

            int createExit = VegCliMain.execute(
                    new String[]{
                            "db", "--db", liveDb.toString(), "create-object",
                            "--type", "Invariant",
                            "--attr", "id=I-91",
                            "--attr", "title=CLI Remote Live Commit",
                            "--id-strategy", "uuid-v7",
                            "--actor", "cli-live",
                            "--reason", "CLI through running Studio server",
                            "--json"
                    },
                    cliOut, cliErr
            );
            assertThat(createExit).isZero();
            assertThat(cliErrBuf.toString(StandardCharsets.UTF_8)).isEmpty();
            Map<String, Object> cliCreated = JsonCodec.parseObject(cliOutBuf.toString(StandardCharsets.UTF_8));
            assertThat(cliCreated.get("commitId")).isEqualTo("c6");
            assertThat(String.valueOf(cliCreated.get("entityId"))).hasSize(36).contains("-");

            // Live server immediately reflects the CLI commit without restart
            assertThat(controller.status().headCommitId()).isEqualTo("c6");
            assertThat(controller.status().commitCount()).isEqualTo(commitsBeforeRemote + 1);
            assertThat(controller.status().entityCount()).isGreaterThan(entitiesBeforeRemote);
            assertThat(controller.recentLogs()).anyMatch(line -> line.contains("cli-live"));

            // 2. MCP stdio handler automatically routes to the same live server (`POST /api/mcp`)
            VegMcpStdioServer liveMcp = VegMcpStdioServer.forWorkspace(liveDb, null, false);
            String mcpRequests = String.join("\n",
                    JsonCodec.toJson(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of())),
                    JsonCodec.toJson(Map.of(
                            "jsonrpc", "2.0", "id", 2, "method", "tools/call",
                            "params", Map.of("name", "veg_mutate", "arguments", Map.of(
                                    "action", "create_object",
                                    "typeName", "Invariant",
                                    "attributes", Map.of("id", "I-92", "title", "MCP Remote Live Commit"),
                                    "actor", "mcp-live",
                                    "reason", "MCP through running Studio server"
                            ))
                    )),
                    JsonCodec.toJson(Map.of(
                            "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                            "params", Map.of("name", "veg_query", "arguments", Map.of(
                                    "dsl", "MATCH (i:Invariant) WHERE i.id = \"I-91\" SELECT i.title"
                            ))
                    ))
            );
            ByteArrayOutputStream mcpOutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream mcpErrBuf = new ByteArrayOutputStream();
            liveMcp.processStream(
                    new BufferedReader(new StringReader(mcpRequests)),
                    new PrintStream(mcpOutBuf, true, StandardCharsets.UTF_8),
                    new PrintStream(mcpErrBuf, true, StandardCharsets.UTF_8)
            );
            assertThat(mcpErrBuf.toString(StandardCharsets.UTF_8)).isEmpty();

            List<String> mcpLines = mcpOutBuf.toString(StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank())
                    .toList();
            assertThat(mcpLines).hasSize(3);
            Map<String, Object> mcpMutatePayload = parseToolTextPayload(mcpLines.get(1));
            assertThat(mcpMutatePayload.get("commitId")).isEqualTo("c7");
            Map<String, Object> mcpQueryPayload = parseToolTextPayload(mcpLines.get(2));
            assertThat(((Number) mcpQueryPayload.get("rowCount")).intValue()).isEqualTo(1);

            assertThat(controller.status().headCommitId()).isEqualTo("c7");
            assertThat(controller.recentLogs()).anyMatch(line -> line.contains("MCP-Tool veg_mutate"));

            // Verify Studio HTTP /api/overview reflects the live head directly
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> ovResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + controller.status().activePort() + "/api/overview"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(ovResp.statusCode()).isEqualTo(200);
            assertThat(JsonCodec.parseObject(ovResp.body()).get("headCommitId")).isEqualTo("c7");

            // Stopping the server removes `server.active`
            controller.stopServer();
            assertThat(Files.exists(activeMarker)).isFalse();
        }

        // 3. After server stop, CLI and MCP transparently fall back to local disk and see c6 + c7
        ByteArrayOutputStream offlineOut = new ByteArrayOutputStream();
        int offlineQueryExit = VegCliMain.execute(
                new String[]{
                        "db", "--db", liveDb.toString(), "query",
                        "--dsl", "MATCH (i:Invariant) WHERE i.id = \"I-92\" SELECT i.title",
                        "--json"
                },
                new PrintStream(offlineOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertThat(offlineQueryExit).isZero();
        Map<String, Object> offlineQuery = JsonCodec.parseObject(offlineOut.toString(StandardCharsets.UTF_8));
        assertThat(((Number) offlineQuery.get("rowCount")).intValue()).isEqualTo(1);
    }

    private static Map<String, Object> parseToolTextPayload(String rpcResponseLine) {
        Map<String, Object> root = JsonCodec.parseObject(rpcResponseLine);
        Map<String, Object> result = JsonCodec.asObject(root.get("result"));
        assertThat(result.get("isError")).isEqualTo(false);
        List<Object> content = JsonCodec.asArray(result.get("content"));
        String jsonText = String.valueOf(JsonCodec.asObject(content.getFirst()).get("text"));
        return JsonCodec.parseObject(jsonText);
    }
}
