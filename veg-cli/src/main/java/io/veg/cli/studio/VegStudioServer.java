package io.veg.cli.studio;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.veg.cli.json.JsonCodec;
import io.veg.cli.kb.GraphTabularService;
import io.veg.cli.kb.GraphTabularService.SnapshotContext;
import io.veg.engine.BranchNotFoundException;
import io.veg.engine.CommitNotFoundException;
import io.veg.engine.QuerySyntaxException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.VegException;
import io.veg.engine.VegPatchService.PatchApplyMode;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.EntityId;
import io.veg.model.Reason;
import io.veg.model.ValueType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP + JSON server powering VEG Access Studio: an MS-Access-inspired
 * graphical browser for inspecting schemas, browsing/editing datasheets, running
 * {@code GraphQueryDsl} queries, traversing relations, and time-traveling commits.
 */
public final class VegStudioServer implements AutoCloseable {

    private final VersionedGraphDatabase db;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Object mutationLock = new Object();

    public VegStudioServer(VersionedGraphDatabase db, int port) throws IOException {
        this.db = Objects.requireNonNull(db, "db must not be null");
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server.setExecutor(this.executor);
        registerRoutes();
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port() + "/studio/index.html";
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void registerRoutes() {
        server.createContext("/", ex -> handleStatic(ex, "/studio/index.html", "text/html; charset=utf-8"));
        server.createContext("/studio", ex -> {
            String path = ex.getRequestURI().getPath();
            if ("/studio".equals(path) || "/studio/".equals(path) || "/studio/index.html".equals(path)) {
                handleStatic(ex, "/studio/index.html", "text/html; charset=utf-8");
            } else if ("/studio/styles.css".equals(path)) {
                handleStatic(ex, "/studio/styles.css", "text/css; charset=utf-8");
            } else if ("/studio/app.js".equals(path)) {
                handleStatic(ex, "/studio/app.js", "application/javascript; charset=utf-8");
            } else {
                sendJson(ex, 404, Map.of("error", "NotFound", "message", "Resource not found: " + path));
            }
        });
        server.createContext("/api/overview", ex -> handleGetJson(ex, q -> {
            SnapshotContext ctx = ctxFromQuery(q);
            return GraphTabularService.overview(db, ctx);
        }));
        server.createContext("/api/datasheet", ex -> handleGetJson(ex, q -> {
            SnapshotContext ctx = ctxFromQuery(q);
            String typeName = requireQueryParam(q, "type");
            Map<String, String> filters = new LinkedHashMap<>();
            if (q.containsKey("q") && !q.get("q").isBlank()) {
                filters.put("_q", q.get("q"));
            }
            for (Map.Entry<String, String> e : q.entrySet()) {
                if (e.getKey().startsWith("filter.") && !e.getValue().isBlank()) {
                    filters.put(e.getKey().substring("filter.".length()), e.getValue());
                }
            }
            String sortBy = q.getOrDefault("sortBy", "_id");
            String sortDir = q.getOrDefault("sortDir", "asc");
            int limit = parseIntOrDefault(q.get("limit"), 200);
            int offset = parseIntOrDefault(q.get("offset"), 0);
            return GraphTabularService.datasheet(ctx, typeName, filters, sortBy, sortDir, limit, offset);
        }));
        server.createContext("/api/entity", ex -> handleGetJson(ex, q -> {
            SnapshotContext ctx = ctxFromQuery(q);
            EntityId id = GraphTabularService.parseEntityId(requireQueryParam(q, "id"));
            return GraphTabularService.entityDetail(db, ctx, id);
        }));
        server.createContext("/api/history", ex -> handleGetJson(ex, q -> GraphTabularService.commitsHistory(db)));
        server.createContext("/api/diff", ex -> handleGetJson(ex, q ->
                GraphTabularService.diffCommits(db, requireQueryParam(q, "from"), requireQueryParam(q, "to"))
        ));
        server.createContext("/api/patch", ex -> {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                handleGetJson(ex, q -> GraphTabularService.exportPatch(
                        db, requireQueryParam(q, "from"), requireQueryParam(q, "to")));
            } else {
                handlePostJson(ex, body -> {
                    synchronized (mutationLock) {
                        String branch = JsonCodec.asOptionalString(body, "branch").orElse(db.currentBranch());
                        String modeRaw = JsonCodec.asOptionalString(body, "mode").orElse("MERGE");
                        PatchApplyMode mode = PatchApplyMode.valueOf(modeRaw.trim().toUpperCase(Locale.ROOT));
                        Actor actor = JsonCodec.asOptionalString(body, "actor").filter(s -> !s.isBlank())
                                .map(Actor::user).orElse(null);
                        Reason reason = JsonCodec.asOptionalString(body, "reason").filter(s -> !s.isBlank())
                                .map(Reason::of).orElse(null);
                        return GraphTabularService.applyOrMergePatch(
                                db, JsonCodec.requireString(body, "patchText"), branch, mode, actor, reason);
                    }
                });
            }
        });
        server.createContext("/api/query", ex -> handlePostJson(ex, body -> {
            SnapshotContext ctx = GraphTabularService.resolveContext(
                    db,
                    JsonCodec.asOptionalString(body, "branch").orElse(null),
                    JsonCodec.asOptionalString(body, "asOf").orElse(null),
                    JsonCodec.asOptionalString(body, "validAt").orElse(null)
            );
            return GraphTabularService.executeDslQuery(ctx, JsonCodec.requireString(body, "dsl"));
        }));
        server.createContext("/api/objects", ex -> handlePostJson(ex, body -> {
            synchronized (mutationLock) {
                String branch = JsonCodec.asOptionalString(body, "branch").orElse(db.currentBranch());
                Actor actor = actorFromBody(body);
                Reason reason = reasonFromBody(body, "Studio: Create object");
                String typeName = JsonCodec.requireString(body, "typeName");
                Map<String, String> attrs = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : JsonCodec.asObject(body.get("attributes")).entrySet()) {
                    if (e.getValue() != null) {
                        attrs.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                }
                return GraphTabularService.createObject(db, branch, actor, reason, typeName, attrs);
            }
        }));
        server.createContext("/api/cell", ex -> handlePostJson(ex, body -> {
            synchronized (mutationLock) {
                String branch = JsonCodec.asOptionalString(body, "branch").orElse(db.currentBranch());
                Actor actor = actorFromBody(body);
                Reason reason = reasonFromBody(body, "Studio: Edit attribute cell");
                EntityId entityId = GraphTabularService.parseEntityId(JsonCodec.requireString(body, "entityId"));
                String predicate = JsonCodec.requireString(body, "predicate");
                boolean clear = JsonCodec.asBoolean(body, "clear", false);
                String value = JsonCodec.asOptionalString(body, "value").orElse(null);
                return GraphTabularService.setAttributeCell(db, branch, actor, reason, entityId, predicate, value, clear);
            }
        }));
        server.createContext("/api/connect", ex -> handlePostJson(ex, body -> {
            synchronized (mutationLock) {
                String branch = JsonCodec.asOptionalString(body, "branch").orElse(db.currentBranch());
                Actor actor = actorFromBody(body);
                Reason reason = reasonFromBody(body, "Studio: Connect relation");
                EntityId sourceId = GraphTabularService.parseEntityId(JsonCodec.requireString(body, "sourceId"));
                String predicate = JsonCodec.requireString(body, "predicate");
                EntityId targetId = GraphTabularService.parseEntityId(JsonCodec.requireString(body, "targetId"));
                return GraphTabularService.connectEntities(db, branch, actor, reason, sourceId, predicate, targetId);
            }
        }));
        server.createContext("/api/disconnect", ex -> handlePostJson(ex, body -> {
            synchronized (mutationLock) {
                String branch = JsonCodec.asOptionalString(body, "branch").orElse(db.currentBranch());
                Actor actor = actorFromBody(body);
                Reason reason = reasonFromBody(body, "Studio: Disconnect edge");
                EntityId edgeId = GraphTabularService.parseEntityId(JsonCodec.requireString(body, "edgeId"));
                return GraphTabularService.disconnectEdge(db, branch, actor, reason, edgeId);
            }
        }));
        server.createContext("/api/schema/object-type", ex -> handlePostJson(ex, body -> {
            synchronized (mutationLock) {
                String branch = JsonCodec.asOptionalString(body, "branch").orElse(db.currentBranch());
                return GraphTabularService.defineObjectType(
                        db, branch, actorFromBody(body), reasonFromBody(body, "Studio: Define ObjectType"),
                        JsonCodec.requireString(body, "name")
                );
            }
        }));
        server.createContext("/api/schema/value-type", ex -> handlePostJson(ex, body -> {
            synchronized (mutationLock) {
                String branch = JsonCodec.asOptionalString(body, "branch").orElse(db.currentBranch());
                ValueType vt = ValueType.valueOf(JsonCodec.requireString(body, "scalarType").toUpperCase(Locale.ROOT));
                boolean unique = JsonCodec.asBoolean(body, "uniqueValues", false);
                return GraphTabularService.defineValueType(
                        db, branch, actorFromBody(body), reasonFromBody(body, "Studio: Define ValueType"),
                        JsonCodec.requireString(body, "name"), vt, unique
                );
            }
        }));
        server.createContext("/api/schema/predicate", ex -> handlePostJson(ex, body -> {
            synchronized (mutationLock) {
                String branch = JsonCodec.asOptionalString(body, "branch").orElse(db.currentBranch());
                OptionalInt maxOut = JsonCodec.asOptionalLong(body, "maxOutgoing")
                        .map(l -> OptionalInt.of(l.intValue())).orElse(OptionalInt.empty());
                OptionalInt maxIn = JsonCodec.asOptionalLong(body, "maxIncoming")
                        .map(l -> OptionalInt.of(l.intValue())).orElse(OptionalInt.empty());
                boolean uniqueTriple = JsonCodec.asBoolean(body, "uniqueTriple", true);
                return GraphTabularService.definePredicate(
                        db, branch, actorFromBody(body), reasonFromBody(body, "Studio: Define Predicate"),
                        JsonCodec.requireString(body, "name"),
                        JsonCodec.requireString(body, "domain"),
                        JsonCodec.requireString(body, "range"),
                        maxOut, maxIn, uniqueTriple
                );
            }
        }));
        server.createContext("/api/checkout", ex -> handlePostJson(ex, body -> {
            synchronized (mutationLock) {
                String branch = JsonCodec.requireString(body, "branch");
                db.checkoutBranch(branch);
                SnapshotContext ctx = GraphTabularService.resolveContext(db, branch, null, null);
                return GraphTabularService.overview(db, ctx);
            }
        }));
    }

    @FunctionalInterface
    private interface QueryHandler {
        Object handle(Map<String, String> queryParams);
    }

    @FunctionalInterface
    private interface BodyHandler {
        Object handle(Map<String, Object> body);
    }

    private void handleGetJson(HttpExchange ex, QueryHandler handler) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "MethodNotAllowed", "message", "Expected GET"));
            return;
        }
        try {
            Map<String, String> params = parseQueryParams(ex.getRequestURI().getRawQuery());
            sendJson(ex, 200, handler.handle(params));
        } catch (Exception e) {
            sendError(ex, e);
        }
    }

    private void handlePostJson(HttpExchange ex, BodyHandler handler) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "MethodNotAllowed", "message", "Expected POST"));
            return;
        }
        try {
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> body = raw.isBlank() ? Map.of() : JsonCodec.parseObject(raw);
            sendJson(ex, 200, handler.handle(body));
        } catch (Exception e) {
            sendError(ex, e);
        }
    }

    private void handleStatic(HttpExchange ex, String resourcePath, String contentType) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "MethodNotAllowed", "message", "Expected GET"));
            return;
        }
        try (InputStream in = VegStudioServer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                sendJson(ex, 404, Map.of("error", "NotFound", "message", "Missing static resource: " + resourcePath));
                return;
            }
            byte[] bytes = in.readAllBytes();
            Headers headers = ex.getResponseHeaders();
            headers.set("Content-Type", contentType);
            headers.set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private void sendError(HttpExchange ex, Exception e) throws IOException {
        int status = switch (e) {
            case IllegalArgumentException ignored -> 400;
            case QuerySyntaxException ignored -> 400;
            case CommitNotFoundException ignored -> 404;
            case BranchNotFoundException ignored -> 404;
            case SchemaViolationException ignored -> 409;
            case VegException ignored -> 409;
            default -> 500;
        };
        sendJson(ex, status, Map.of(
                "error", e.getClass().getSimpleName(),
                "message", e.getMessage() != null ? e.getMessage() : "Internal server error"
        ));
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = JsonCodec.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private SnapshotContext ctxFromQuery(Map<String, String> q) {
        return GraphTabularService.resolveContext(
                db,
                q.get("branch"),
                q.get("asOf"),
                q.get("validAt")
        );
    }

    private static Actor actorFromBody(Map<String, Object> body) {
        String id = JsonCodec.asOptionalString(body, "actor").filter(s -> !s.isBlank()).orElse("studio-user");
        return Actor.of(id, id);
    }

    private static Reason reasonFromBody(Map<String, Object> body, String defaultText) {
        String text = JsonCodec.asOptionalString(body, "reason").filter(s -> !s.isBlank()).orElse(defaultText);
        return Reason.of(text);
    }

    private static String requireQueryParam(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing query parameter: " + key);
        }
        return v;
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(raw.trim());
    }

    private static Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
            String v = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
            map.put(k, v);
        }
        return map;
    }
}
