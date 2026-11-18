package io.veg.cli.studio;

import io.veg.cli.json.JsonCodec;
import io.veg.engine.VegPatchService.PatchApplyMode;
import io.veg.model.IdGenerationStrategy;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * HTTP client and server discovery helper allowing {@code veg-cli db} and {@code veg-cli mcp}
 * to transparently talk to a running {@link VegStudioServer} ({@code VEG-Studio.exe},
 * {@code veg-cli desktop}, or {@code veg-cli studio}) on the active database.
 */
public final class VegRemoteServerClient {

    public static final String ACTIVE_SERVER_FILE = "server.active";
    private static final String DEFAULT_SERVER_ORIGIN = "http://127.0.0.1:8080";
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(750);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT)
            .build();

    private VegRemoteServerClient() {
    }

    /**
     * Resolves the origin URL (e.g. {@code http://127.0.0.1:8080}) of a running VEG server.
     *
     * <ol>
     *     <li>If {@code forceLocal} is {@code true}, always returns {@link Optional#empty()}.</li>
     *     <li>If {@code explicitServerUrl} is given, validates reachability and returns it or throws.</li>
     *     <li>If {@code dbDir/server.active} points to a live server matching {@code dbDir}, returns it.</li>
     *     <li>If targeting the default {@code data/project-kb} workspace (or omitted), probes {@code http://127.0.0.1:8080}.</li>
     * </ol>
     */
    public static Optional<String> resolveRunningServer(Path dbDir, String explicitServerUrl, boolean forceLocal) {
        if (forceLocal) {
            return Optional.empty();
        }
        if (explicitServerUrl != null && !explicitServerUrl.isBlank()) {
            String origin = normalizeOriginUrl(explicitServerUrl);
            Map<String, Object> info = probeServerInfo(origin);
            if (info == null) {
                throw new IllegalStateException("VEG server not reachable at " + origin);
            }
            return Optional.of(origin);
        }

        if (dbDir != null) {
            Path normalizedTarget = dbDir.toAbsolutePath().normalize();
            Path activeFile = normalizedTarget.resolve(ACTIVE_SERVER_FILE);
            if (Files.isRegularFile(activeFile)) {
                try {
                    String candidateUrl = Files.readString(activeFile, StandardCharsets.UTF_8).trim();
                    if (!candidateUrl.isBlank()) {
                        String origin = normalizeOriginUrl(candidateUrl);
                        Map<String, Object> info = probeServerInfo(origin);
                        if (info != null && "running".equals(info.get("status"))) {
                            String remoteDbDirRaw = String.valueOf(info.getOrDefault("dbDir", ""));
                            if (remoteDbDirRaw.isBlank()
                                    || Path.of(remoteDbDirRaw).toAbsolutePath().normalize().equals(normalizedTarget)) {
                                return Optional.of(origin);
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // Treat unreadable discovery marker as absent and fall back cleanly.
                }
            }
        }

        if (dbDir == null || dbDir.normalize().endsWith(Path.of("data", "project-kb"))) {
            Map<String, Object> info = probeServerInfo(DEFAULT_SERVER_ORIGIN);
            if (info != null && "running".equals(info.get("status"))) {
                return Optional.of(DEFAULT_SERVER_ORIGIN);
            }
        }
        return Optional.empty();
    }

    public static String normalizeOriginUrl(String rawUrl) {
        Objects.requireNonNull(rawUrl, "rawUrl must not be null");
        String trimmed = rawUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        URI uri = URI.create(trimmed);
        String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
        String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
        int port = uri.getPort();
        return port >= 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
    }

    public static Map<String, Object> probeServerInfo(String originUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(originUrl + "/api/server-info"))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return null;
            }
            return JsonCodec.parseObject(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String forwardMcpJsonRpc(String originUrl, String rawJsonRpc) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(originUrl + "/api/mcp"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(rawJsonRpc, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 204) {
                return null;
            }
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("Remote MCP request failed (" + resp.statusCode() + "): " + extractErrorMessage(resp.body()));
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for remote VEG MCP response", e);
        }
    }

    public static Map<String, Object> executeRemoteDbSubcommand(
            String originUrl,
            String subcommand,
            String branch,
            String asOf,
            String validAt,
            String typeName,
            String search,
            String sortBy,
            String sortDir,
            int limit,
            int offset,
            String dsl,
            String entityIdStr,
            String sourceIdStr,
            String targetIdStr,
            String edgeIdStr,
            String predicate,
            String value,
            boolean clear,
            String actorId,
            String reasonText,
            boolean hasExplicitReason,
            IdGenerationStrategy idStrategy,
            Map<String, String> createAttrs,
            String fromCommitStr,
            String toCommitStr,
            Path patchInputFile,
            Path patchOutputFile,
            PatchApplyMode patchMode
    ) throws IOException {
        return switch (subcommand) {
            case "schema" -> getJson(originUrl + "/api/overview" + buildContextQuery(branch, asOf, validAt, null));
            case "datasheet" -> {
                if (typeName == null) {
                    throw new IllegalArgumentException("Missing required option --type");
                }
                Map<String, String> params = new LinkedHashMap<>();
                params.put("type", typeName);
                if (search != null && !search.isBlank()) {
                    params.put("q", search);
                }
                params.put("sortBy", sortBy);
                params.put("sortDir", sortDir);
                params.put("limit", Integer.toString(limit));
                params.put("offset", Integer.toString(offset));
                yield getJson(originUrl + "/api/datasheet" + buildContextQuery(branch, asOf, validAt, params));
            }
            case "query" -> {
                if (dsl == null) {
                    throw new IllegalArgumentException("Missing required option --dsl");
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("dsl", dsl);
                putIfNotBlank(body, "branch", branch);
                putIfNotBlank(body, "asOf", asOf);
                putIfNotBlank(body, "validAt", validAt);
                yield postJson(originUrl + "/api/query", body);
            }
            case "inspect" -> {
                if (entityIdStr == null) {
                    throw new IllegalArgumentException("Missing required option --id");
                }
                yield getJson(originUrl + "/api/entity" + buildContextQuery(branch, asOf, validAt, Map.of("id", entityIdStr)));
            }
            case "commits" -> getJson(originUrl + "/api/history");
            case "set-attr" -> {
                if (entityIdStr == null || predicate == null) {
                    throw new IllegalArgumentException("Missing required options --id and --pred");
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("entityId", entityIdStr);
                body.put("predicate", predicate);
                body.put("clear", clear);
                if (value != null) {
                    body.put("value", value);
                }
                putAuditFields(body, branch, actorId, reasonText);
                yield postJson(originUrl + "/api/cell", body);
            }
            case "create-object" -> {
                if (typeName == null) {
                    throw new IllegalArgumentException("Missing required option --type");
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("typeName", typeName);
                body.put("attributes", createAttrs != null ? createAttrs : Map.of());
                if (idStrategy != null) {
                    body.put("idStrategy", idStrategy.name());
                }
                putAuditFields(body, branch, actorId, reasonText);
                yield postJson(originUrl + "/api/objects", body);
            }
            case "connect" -> {
                if (sourceIdStr == null || predicate == null || targetIdStr == null) {
                    throw new IllegalArgumentException("Missing required options --source, --pred, --target");
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("sourceId", sourceIdStr);
                body.put("predicate", predicate);
                body.put("targetId", targetIdStr);
                putAuditFields(body, branch, actorId, reasonText);
                yield postJson(originUrl + "/api/connect", body);
            }
            case "disconnect" -> {
                if (edgeIdStr == null) {
                    throw new IllegalArgumentException("Missing required option --edge");
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("edgeId", edgeIdStr);
                putAuditFields(body, branch, actorId, reasonText);
                yield postJson(originUrl + "/api/disconnect", body);
            }
            case "export-patch" -> {
                if (fromCommitStr == null || toCommitStr == null) {
                    throw new IllegalArgumentException("Missing required options --from and --to");
                }
                Map<String, Object> exported = getJson(originUrl + "/api/patch"
                        + encodeQueryParams(Map.of("from", fromCommitStr, "to", toCommitStr)));
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
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("patchText", patchText);
                body.put("mode", patchMode.name());
                putIfNotBlank(body, "branch", branch);
                if (!"cli-agent".equals(actorId) || hasExplicitReason) {
                    body.put("actor", actorId);
                }
                if (hasExplicitReason) {
                    body.put("reason", reasonText);
                }
                yield postJson(originUrl + "/api/patch", body);
            }
            default -> throw new IllegalArgumentException("Unknown db subcommand: " + subcommand);
        };
    }

    private static Map<String, Object> getJson(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return sendExpectJson(req);
    }

    private static Map<String, Object> postJson(String url, Map<String, Object> payload) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.toJson(payload), StandardCharsets.UTF_8))
                .build();
        return sendExpectJson(req);
    }

    private static Map<String, Object> sendExpectJson(HttpRequest req) throws IOException {
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                throw new IllegalArgumentException(extractErrorMessage(resp.body()));
            }
            return JsonCodec.parseObject(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for remote VEG server", e);
        }
    }

    private static String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "Remote server error";
        }
        try {
            Map<String, Object> parsed = JsonCodec.parseObject(body);
            return JsonCodec.asOptionalString(parsed, "message").orElse(body);
        } catch (Exception ignored) {
            return body;
        }
    }

    private static void putAuditFields(Map<String, Object> body, String branch, String actorId, String reasonText) {
        putIfNotBlank(body, "branch", branch);
        putIfNotBlank(body, "actor", actorId);
        putIfNotBlank(body, "reason", reasonText);
    }

    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String buildContextQuery(
            String branch,
            String asOf,
            String validAt,
            Map<String, String> extraParams
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        if (branch != null && !branch.isBlank()) {
            params.put("branch", branch);
        }
        if (asOf != null && !asOf.isBlank()) {
            params.put("asOf", asOf);
        }
        if (validAt != null && !validAt.isBlank()) {
            params.put("validAt", validAt);
        }
        if (extraParams != null) {
            params.putAll(extraParams);
        }
        return encodeQueryParams(params);
    }

    private static String encodeQueryParams(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("&", "?", "");
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() != null) {
                joiner.add(urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()));
            }
        }
        return joiner.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
