package io.veg.cli.mcp;

import io.veg.cli.json.JsonCodec;
import io.veg.cli.kb.GraphTabularService;
import io.veg.cli.kb.GraphTabularService.SnapshotContext;
import io.veg.engine.VegPatchService.PatchApplyMode;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.EntityId;
import io.veg.model.IdGenerationStrategy;
import io.veg.model.Reason;
import io.veg.model.ValueType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Model Context Protocol (MCP) JSON-RPC 2.0 stdio server exposing VEG database schema,
 * tabular browsing, {@code GraphQueryDsl} queries, entity inspection, and transactional mutations
 * to coding agents.
 */
public final class VegMcpStdioServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final VersionedGraphDatabase db;

    public VegMcpStdioServer(VersionedGraphDatabase db) {
        this.db = Objects.requireNonNull(db, "db must not be null");
    }

    public void runLoop(InputStream in, PrintStream out, PrintStream err) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            processStream(reader, out, err);
        }
    }

    public void processStream(BufferedReader reader, PrintStream out, PrintStream err) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Also tolerate Content-Length framing if an older client sends LSP-style headers
            if (trimmed.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                int len = Integer.parseInt(trimmed.substring("Content-Length:".length()).trim());
                String headerLine;
                while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                    // consume remaining headers
                }
                char[] buf = new char[len];
                int read = 0;
                while (read < len) {
                    int n = reader.read(buf, read, len - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                trimmed = new String(buf, 0, read).trim();
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            String responseJson = handleJsonRpcLine(trimmed, err);
            if (responseJson != null) {
                synchronized (out) {
                    out.println(responseJson);
                    out.flush();
                }
            }
        }
    }

    public String handleJsonRpcLine(String rawJson, PrintStream err) {
        Object id = null;
        try {
            Map<String, Object> req = JsonCodec.parseObject(rawJson);
            id = req.get("id");
            String method = JsonCodec.requireString(req, "method");
            Map<String, Object> params = JsonCodec.asObject(req.get("params"));

            // Notifications have no id and expect no JSON-RPC response
            if (method.startsWith("notifications/")) {
                return null;
            }

            Object result = switch (method) {
                case "initialize" -> handleInitialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", toolDefinitions());
                case "tools/call" -> handleToolCall(params);
                default -> throw new MethodNotFoundException("Unsupported MCP method: " + method);
            };
            return JsonCodec.toJson(rpcResult(id, result));
        } catch (MethodNotFoundException e) {
            return id != null ? JsonCodec.toJson(rpcError(id, -32601, e.getMessage())) : null;
        } catch (Exception e) {
            if (err != null) {
                err.println("[veg-mcp] Error handling request: " + e.getMessage());
            }
            return id != null ? JsonCodec.toJson(rpcError(id, -32603, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())) : null;
        }
    }

    private Map<String, Object> handleInitialize(Map<String, Object> params) {
        String requested = JsonCodec.asOptionalString(params, "protocolVersion").orElse(PROTOCOL_VERSION);
        Map<String, Object> serverInfo = Map.of(
                "name", "veg-mcp",
                "version", "0.1.0"
        );
        Map<String, Object> capabilities = Map.of(
                "tools", Map.of("listChanged", false)
        );
        Map<String, Object> init = new LinkedHashMap<>();
        init.put("protocolVersion", requested.isBlank() ? PROTOCOL_VERSION : requested);
        init.put("capabilities", capabilities);
        init.put("serverInfo", serverInfo);
        init.put("instructions", "Query and edit the Versioned Entity Graph (VEG) database. Start with veg_schema to inspect available ObjectTypes, Predicates, and Branches.");
        return init;
    }

    private Map<String, Object> handleToolCall(Map<String, Object> params) {
        String toolName = JsonCodec.requireString(params, "name");
        Map<String, Object> args = JsonCodec.asObject(params.get("arguments"));
        try {
            Object payload = switch (toolName) {
                case "veg_schema" -> {
                    SnapshotContext ctx = ctxFromArgs(args);
                    yield GraphTabularService.overview(db, ctx);
                }
                case "veg_datasheet" -> {
                    SnapshotContext ctx = ctxFromArgs(args);
                    String typeName = JsonCodec.requireString(args, "typeName");
                    Map<String, String> filters = new LinkedHashMap<>();
                    JsonCodec.asOptionalString(args, "search").ifPresent(q -> filters.put("_q", q));
                    Map<String, Object> rawFilters = JsonCodec.asObject(args.get("filters"));
                    for (Map.Entry<String, Object> e : rawFilters.entrySet()) {
                        if (e.getValue() != null) {
                            filters.put(e.getKey(), String.valueOf(e.getValue()));
                        }
                    }
                    String sortBy = JsonCodec.asOptionalString(args, "sortBy").orElse("_id");
                    String sortDir = JsonCodec.asOptionalString(args, "sortDir").orElse("asc");
                    int limit = JsonCodec.asInt(args, "limit", 100);
                    int offset = JsonCodec.asInt(args, "offset", 0);
                    yield GraphTabularService.datasheet(ctx, typeName, filters, sortBy, sortDir, limit, offset);
                }
                case "veg_query" -> {
                    SnapshotContext ctx = ctxFromArgs(args);
                    String dsl = JsonCodec.requireString(args, "dsl");
                    yield GraphTabularService.executeDslQuery(ctx, dsl);
                }
                case "veg_entity" -> {
                    SnapshotContext ctx = ctxFromArgs(args);
                    EntityId id = GraphTabularService.parseEntityId(JsonCodec.requireString(args, "entityId"));
                    yield GraphTabularService.entityDetail(db, ctx, id);
                }
                case "veg_history" -> GraphTabularService.commitsHistory(db);
                case "veg_patch" -> executePatchTool(args);
                case "veg_mutate" -> executeMutate(args);
                default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
            };
            return Map.of(
                    "content", List.of(Map.of(
                            "type", "text",
                            "text", JsonCodec.toJson(payload)
                    )),
                    "isError", false
            );
        } catch (Exception e) {
            return Map.of(
                    "content", List.of(Map.of(
                            "type", "text",
                            "text", JsonCodec.toJson(Map.of(
                                    "error", e.getClass().getSimpleName(),
                                    "message", e.getMessage() != null ? e.getMessage() : "Tool call failed"
                            ))
                    )),
                    "isError", true
            );
        }
    }

    private Map<String, Object> executeMutate(Map<String, Object> args) {
        String action = JsonCodec.requireString(args, "action").toLowerCase(Locale.ROOT);
        String branch = JsonCodec.asOptionalString(args, "branch").orElse(db.currentBranch());
        String actorId = JsonCodec.asOptionalString(args, "actor").orElse("coding-agent");
        String reasonText = JsonCodec.asOptionalString(args, "reason").orElse("MCP agent mutation: " + action);
        Actor actor = Actor.of(actorId, actorId);
        Reason reason = Reason.of(reasonText);

        return switch (action) {
            case "create_object" -> {
                String typeName = JsonCodec.requireString(args, "typeName");
                Map<String, Object> rawAttrs = JsonCodec.asObject(args.get("attributes"));
                Map<String, String> attrs = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : rawAttrs.entrySet()) {
                    if (e.getValue() != null) {
                        attrs.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                }
                IdGenerationStrategy strategy = JsonCodec.asOptionalString(args, "idStrategy")
                        .filter(s -> !s.isBlank())
                        .map(s -> IdGenerationStrategy.valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_')))
                        .orElse(null);
                yield GraphTabularService.createObject(db, branch, actor, reason, typeName, attrs, strategy);
            }
            case "set_attribute" -> {
                EntityId entityId = GraphTabularService.parseEntityId(JsonCodec.requireString(args, "entityId"));
                String predicate = JsonCodec.requireString(args, "predicate");
                boolean clear = JsonCodec.asBoolean(args, "clear", false);
                String value = JsonCodec.asOptionalString(args, "value").orElse(null);
                yield GraphTabularService.setAttributeCell(db, branch, actor, reason, entityId, predicate, value, clear);
            }
            case "connect" -> {
                EntityId sourceId = GraphTabularService.parseEntityId(JsonCodec.requireString(args, "sourceId"));
                String predicate = JsonCodec.requireString(args, "predicate");
                EntityId targetId = GraphTabularService.parseEntityId(JsonCodec.requireString(args, "targetId"));
                yield GraphTabularService.connectEntities(db, branch, actor, reason, sourceId, predicate, targetId);
            }
            case "disconnect" -> {
                EntityId edgeId = GraphTabularService.parseEntityId(JsonCodec.requireString(args, "edgeId"));
                yield GraphTabularService.disconnectEdge(db, branch, actor, reason, edgeId);
            }
            case "define_object_type" -> {
                String name = JsonCodec.requireString(args, "name");
                yield GraphTabularService.defineObjectType(db, branch, actor, reason, name);
            }
            case "define_value_type" -> {
                String name = JsonCodec.requireString(args, "name");
                ValueType scalar = ValueType.valueOf(JsonCodec.requireString(args, "scalarType").toUpperCase(Locale.ROOT));
                boolean unique = JsonCodec.asBoolean(args, "uniqueValues", false);
                yield GraphTabularService.defineValueType(db, branch, actor, reason, name, scalar, unique);
            }
            case "define_predicate" -> {
                String name = JsonCodec.requireString(args, "name");
                String domain = JsonCodec.requireString(args, "domain");
                String range = JsonCodec.requireString(args, "range");
                OptionalInt maxOut = JsonCodec.asOptionalLong(args, "maxOutgoing")
                        .map(l -> OptionalInt.of(l.intValue())).orElse(OptionalInt.empty());
                OptionalInt maxIn = JsonCodec.asOptionalLong(args, "maxIncoming")
                        .map(l -> OptionalInt.of(l.intValue())).orElse(OptionalInt.empty());
                boolean uniqueTriple = JsonCodec.asBoolean(args, "uniqueTriple", true);
                yield GraphTabularService.definePredicate(db, branch, actor, reason, name, domain, range, maxOut, maxIn, uniqueTriple);
            }
            default -> throw new IllegalArgumentException("Unsupported mutate action: " + action);
        };
    }

    private SnapshotContext ctxFromArgs(Map<String, Object> args) {
        String branch = JsonCodec.asOptionalString(args, "branch").orElse(null);
        String asOf = JsonCodec.asOptionalString(args, "asOf").orElse(null);
        String validAt = JsonCodec.asOptionalString(args, "validAt").orElse(null);
        return GraphTabularService.resolveContext(db, branch, asOf, validAt);
    }

    private List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(toolSpec("veg_schema",
                "Inspect VEG database schema (ObjectTypes with row counts, ValueTypes, Predicates with domain/range/cardinality, Branches, and Tags).",
                Map.of(
                        "branch", prop("string", "Optional branch name (defaults to current branch)."),
                        "asOf", prop("string", "Optional historical commit ID such as 'c3' for read-only time-travel."),
                        "validAt", prop("string", "Optional ISO-8601 timestamp or YYYY-MM-DD for bitemporal filtering.")
                ),
                List.of()));

        tools.add(toolSpec("veg_datasheet",
                "Browse instances of an ObjectType or ValueType as a sortable, filterable MS-Access-style table.",
                Map.of(
                        "typeName", prop("string", "Schema type name (e.g. 'Invariant', 'ArchitectureDecision', 'Stage', 'Module')."),
                        "search", prop("string", "Optional global case-insensitive substring filter across all columns."),
                        "filters", prop("object", "Optional map of columnName -> substring filter."),
                        "sortBy", prop("string", "Column name or '_id' to sort by."),
                        "sortDir", prop("string", "'asc' or 'desc'."),
                        "limit", prop("integer", "Maximum rows to return (default 100)."),
                        "offset", prop("integer", "Row offset for pagination (default 0)."),
                        "branch", prop("string", "Optional branch name."),
                        "asOf", prop("string", "Optional commit ID for time-travel.")
                ),
                List.of("typeName")));

        tools.add(toolSpec("veg_query",
                "Execute a GraphQueryDsl query against the VEG database. Syntax: MATCH (a:Type) [JOIN (a)-[pred]->(b[:TargetType])] [WHERE a.attr = \"val\"] [SELECT a.attr, ...] [ORDER BY ...] [LIMIT n].",
                Map.of(
                        "dsl", prop("string", "GraphQueryDsl query string."),
                        "branch", prop("string", "Optional branch name."),
                        "asOf", prop("string", "Optional commit ID for historical snapshot queries."),
                        "validAt", prop("string", "Optional ISO-8601 valid time.")
                ),
                List.of("dsl")));

        tools.add(toolSpec("veg_entity",
                "Inspect an entity's attributes, outgoing relations, incoming relations, validity window, and commit history.",
                Map.of(
                        "entityId", prop("string", "Numeric ID or '#1025' identifier of the entity."),
                        "branch", prop("string", "Optional branch name."),
                        "asOf", prop("string", "Optional commit ID.")
                ),
                List.of("entityId")));

        tools.add(toolSpec("veg_history",
                "List commits in the VEG version history DAG (commitId, parents, branch, dataCategory, actor, reason, timestamp).",
                Map.of(),
                List.of()));

        Map<String, Object> mutateProps = new LinkedHashMap<>();
        mutateProps.put("action", prop("string", "One of: create_object, set_attribute, connect, disconnect, define_object_type, define_value_type, define_predicate."));
        mutateProps.put("branch", prop("string", "Optional target branch (defaults to current branch)."));
        mutateProps.put("actor", prop("string", "Actor identifier recorded in commit audit metadata."));
        mutateProps.put("reason", prop("string", "Human-readable audit reason recorded on the commit."));
        mutateProps.put("typeName", prop("string", "ObjectType name for create_object."));
        mutateProps.put("attributes", prop("object", "Map of attribute predicate -> value for create_object."));
        mutateProps.put("entityId", prop("string", "Target entity ID for set_attribute."));
        mutateProps.put("predicate", prop("string", "Predicate name for set_attribute or connect."));
        mutateProps.put("value", prop("string", "New value text for set_attribute."));
        mutateProps.put("clear", prop("boolean", "Set true in set_attribute to clear/disconnect existing attribute edges."));
        mutateProps.put("sourceId", prop("string", "Source entity ID for connect."));
        mutateProps.put("targetId", prop("string", "Target entity ID for connect."));
        mutateProps.put("edgeId", prop("string", "Edge ID for disconnect."));
        mutateProps.put("name", prop("string", "Schema element name for define_* actions."));
        mutateProps.put("scalarType", prop("string", "STRING|INT|DECIMAL|BOOLEAN|TIMESTAMP|BINARY for define_value_type."));
        mutateProps.put("uniqueValues", prop("boolean", "Whether ValueType enforces global uniqueness."));
        mutateProps.put("domain", prop("string", "Domain type name or ANY_OBJECT/ANY_EDGE/ANY_ENTITY for define_predicate."));
        mutateProps.put("range", prop("string", "Range type name or ANY_OBJECT/ANY_VALUE/ANY_ENTITY for define_predicate."));
        mutateProps.put("maxOutgoing", prop("integer", "Optional maxOutgoing cardinality."));
        mutateProps.put("maxIncoming", prop("integer", "Optional maxIncoming cardinality."));
        mutateProps.put("uniqueTriple", prop("boolean", "Whether duplicate (s, p, t) triples are forbidden."));
        tools.add(toolSpec("veg_mutate",
                "Execute an atomic schema or data mutation commit in the VEG database.",
                mutateProps,
                List.of("action")));

        Map<String, Object> patchProps = new LinkedHashMap<>();
        patchProps.put("action", prop("string", "One of: export, apply, merge."));
        patchProps.put("fromCommit", prop("string", "Base commit ID (e.g. 'c4') for export."));
        patchProps.put("toCommit", prop("string", "Target commit ID (e.g. 'c5') for export."));
        patchProps.put("patchText", prop("string", "Raw .vegpatch text for apply or merge."));
        patchProps.put("branch", prop("string", "Target branch (defaults to current branch)."));
        patchProps.put("actor", prop("string", "Optional actor identifier override."));
        patchProps.put("reason", prop("string", "Optional audit reason override."));
        tools.add(toolSpec("veg_patch",
                "Export a Git-friendly .vegpatch diff between commits, or apply / 3-way merge a .vegpatch into a branch.",
                patchProps,
                List.of("action")));

        return tools;
    }

    private Object executePatchTool(Map<String, Object> args) {
        String action = JsonCodec.requireString(args, "action").toLowerCase(Locale.ROOT);
        return switch (action) {
            case "export" -> GraphTabularService.exportPatch(
                    db,
                    JsonCodec.requireString(args, "fromCommit"),
                    JsonCodec.requireString(args, "toCommit")
            );
            case "apply", "merge" -> {
                PatchApplyMode mode = "apply".equals(action) ? PatchApplyMode.APPLY : PatchApplyMode.MERGE;
                String branch = JsonCodec.asOptionalString(args, "branch").orElse(db.currentBranch());
                Actor actor = JsonCodec.asOptionalString(args, "actor").filter(s -> !s.isBlank())
                        .map(Actor::user).orElse(null);
                Reason reason = JsonCodec.asOptionalString(args, "reason").filter(s -> !s.isBlank())
                        .map(Reason::of).orElse(null);
                yield GraphTabularService.applyOrMergePatch(
                        db, JsonCodec.requireString(args, "patchText"), branch, mode, actor, reason
                );
            }
            default -> throw new IllegalArgumentException("Unsupported veg_patch action: " + action);
        };
    }

    private static Map<String, Object> toolSpec(
            String name,
            String description,
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", schema
        );
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> rpcResult(Object id, Object result) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("jsonrpc", "2.0");
        res.put("id", id);
        res.put("result", result);
        return res;
    }

    private static Map<String, Object> rpcError(Object id, int code, String message) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("jsonrpc", "2.0");
        res.put("id", id);
        res.put("error", Map.of("code", code, "message", message));
        return res;
    }

    private static final class MethodNotFoundException extends RuntimeException {
        MethodNotFoundException(String message) {
            super(message);
        }
    }
}
