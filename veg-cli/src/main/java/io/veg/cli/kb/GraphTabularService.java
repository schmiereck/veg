package io.veg.cli.kb;

import io.veg.engine.GraphReader;
import io.veg.engine.GraphTransaction;
import io.veg.engine.VegPatchService;
import io.veg.engine.VegPatchService.PatchApplyMode;
import io.veg.engine.VegPatchService.PatchResult;
import io.veg.engine.VersionedGraphDatabase;
import io.veg.model.Actor;
import io.veg.model.BootstrapIds;
import io.veg.model.Branch;
import io.veg.model.Change;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.GraphDiff;
import io.veg.model.IdGenerationStrategy;
import io.veg.model.PredicateConstraints;
import io.veg.model.QueryRow;
import io.veg.model.Reason;
import io.veg.model.Tag;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Shared read/query/schema/mutation facade powering the CLI {@code db} command,
 * the MCP stdio tool server, and VEG Access Studio.
 */
public final class GraphTabularService {

    private static final List<String> LABEL_PREDICATES = List.of(
            "title", "name", "term", "id", "code", "label"
    );

    public record SnapshotContext(
            GraphReader reader,
            String branchName,
            CommitId commitId,
            boolean historical,
            Instant validAt
    ) {
    }

    private record PredicateMeta(
            EntityId id,
            String name,
            EntityId domainId,
            String domainName,
            EntityId rangeId,
            String rangeName,
            String rangeKind,
            ValueType scalarType,
            PredicateConstraints constraints
    ) {
    }

    private GraphTabularService() {
    }

    public static SnapshotContext resolveContext(
            VersionedGraphDatabase db,
            String branch,
            String asOfCommit,
            String validAtIso
    ) {
        Objects.requireNonNull(db, "db must not be null");
        String effectiveBranch = (branch != null && !branch.isBlank()) ? branch.trim() : db.currentBranch();
        CommitId resolvedCommit;
        GraphReader reader;
        boolean historical = false;

        if (asOfCommit != null && !asOfCommit.isBlank()) {
            resolvedCommit = parseCommitId(asOfCommit.trim());
            reader = db.asOf(resolvedCommit);
            historical = true;
        } else {
            resolvedCommit = db.branchHead(effectiveBranch);
            reader = db.branchSnapshot(effectiveBranch);
        }

        Instant validAt = null;
        if (validAtIso != null && !validAtIso.isBlank()) {
            validAt = parseTimestamp(validAtIso.trim());
            reader = reader.validAt(validAt);
        }
        return new SnapshotContext(reader, effectiveBranch, resolvedCommit, historical, validAt);
    }

    public static CommitId parseCommitId(String text) {
        String normalized = text.trim();
        if (normalized.startsWith("c") || normalized.startsWith("C")) {
            normalized = normalized.substring(1);
        }
        return new CommitId(Long.parseLong(normalized));
    }

    public static EntityId parseEntityId(String text) {
        return EntityId.parse(text);
    }

    private static Object jsonId(EntityId id) {
        return id.isUuid() ? id.compactString() : id.value();
    }

    public static Map<String, Object> overview(VersionedGraphDatabase db, SnapshotContext ctx) {
        GraphReader reader = ctx.reader();
        Map<EntityId, Long> countsByType = new HashMap<>();
        for (EntityNode node : reader.allEntities()) {
            countsByType.merge(node.typeId(), 1L, Long::sum);
        }
        Map<EntityId, Long> countsByPredicate = new HashMap<>();
        for (Edge edge : reader.allEdges()) {
            countsByPredicate.merge(edge.predicateId(), 1L, Long::sum);
        }

        List<Map<String, Object>> objectTypes = reader.findEntitiesByType(BootstrapIds.META_OBJECT_TYPE).stream()
                .filter(n -> !n.id().isBootstrap())
                .sorted(Comparator.comparing(n -> resolveTypeName(reader, n.id())))
                .map(n -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", jsonId(n.id()));
                    row.put("name", resolveTypeName(reader, n.id()));
                    row.put("count", countsByType.getOrDefault(n.id(), 0L));
                    return row;
                })
                .toList();

        List<Map<String, Object>> valueTypes = reader.findEntitiesByType(BootstrapIds.META_VALUE_TYPE).stream()
                .filter(n -> !n.id().isBootstrap())
                .sorted(Comparator.comparing(n -> resolveTypeName(reader, n.id())))
                .map(n -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", jsonId(n.id()));
                    row.put("name", resolveTypeName(reader, n.id()));
                    row.put("scalarType", resolveValueTypeScalar(reader, n.id()).map(Enum::name).orElse("UNKNOWN"));
                    row.put("uniqueValues", reader.isUniqueValueType(n.id()));
                    row.put("count", countsByType.getOrDefault(n.id(), 0L));
                    return row;
                })
                .toList();

        List<Map<String, Object>> predicates = readPredicates(reader).stream()
                .sorted(Comparator.comparing(PredicateMeta::name))
                .map(pm -> predicateToMap(pm, countsByPredicate.getOrDefault(pm.id(), 0L)))
                .toList();

        List<Map<String, Object>> branches = db.listBranches().stream()
                .sorted(Comparator.comparing(Branch::name))
                .map(b -> Map.<String, Object>of(
                        "name", b.name(),
                        "headCommitId", b.headCommitId().toString()
                ))
                .toList();

        List<Map<String, Object>> tags = db.listTags().stream()
                .sorted(Comparator.comparing(Tag::name))
                .map(t -> Map.<String, Object>of(
                        "name", t.name(),
                        "commitId", t.commitId().toString()
                ))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentBranch", db.currentBranch());
        result.put("activeBranch", ctx.branchName());
        result.put("headCommitId", db.headCommitId().toString());
        result.put("commitId", ctx.commitId().toString());
        result.put("historical", ctx.historical());
        result.put("validAt", ctx.validAt() != null ? ctx.validAt().toString() : null);
        result.put("entityCount", reader.entityCount());
        result.put("edgeCount", reader.edgeCount());
        result.put("branches", branches);
        result.put("tags", tags);
        result.put("objectTypes", objectTypes);
        result.put("valueTypes", valueTypes);
        result.put("predicates", predicates);
        return result;
    }

    public static Map<String, Object> datasheet(
            SnapshotContext ctx,
            String typeName,
            Map<String, String> filters,
            String sortBy,
            String sortDir,
            int limit,
            int offset
    ) {
        GraphReader reader = ctx.reader();
        EntityId typeId = reader.findTypeByName(typeName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown schema type: " + typeName));
        EntityNode typeNode = reader.findEntity(typeId)
                .orElseThrow(() -> new IllegalArgumentException("Type node not found: " + typeId));
        boolean isValueType = BootstrapIds.META_VALUE_TYPE.equals(typeNode.typeId())
                || (typeNode.id().isBootstrap() && resolveBootstrapScalar(typeNode.id()).isPresent());

        List<EntityNode> instances = reader.findEntitiesByType(typeId);
        List<PredicateMeta> allPreds = readPredicates(reader);
        Map<String, PredicateMeta> predByName = new LinkedHashMap<>();
        for (PredicateMeta pm : allPreds) {
            predByName.put(pm.name(), pm);
        }

        List<PredicateMeta> columns = isValueType
                ? List.of()
                : selectColumnsForObjectType(reader, typeId, instances, allPreds, predByName);

        List<Map<String, Object>> columnSpecs = new ArrayList<>();
        if (isValueType) {
            String scalar = resolveValueTypeScalar(reader, typeId).map(Enum::name).orElse("STRING");
            columnSpecs.add(Map.of(
                    "name", "_value",
                    "kind", "VALUE_PAYLOAD",
                    "scalarType", scalar,
                    "editable", true,
                    "multiValued", false
            ));
        } else {
            for (PredicateMeta col : columns) {
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("name", col.name());
                spec.put("predicateId", jsonId(col.id()));
                spec.put("kind", "VALUE_TYPE".equals(col.rangeKind()) ? "ATTRIBUTE" : "RELATION");
                spec.put("rangeType", col.rangeName());
                spec.put("scalarType", col.scalarType() != null ? col.scalarType().name() : null);
                boolean singleValued = col.constraints().maxOutgoing().isPresent()
                        && col.constraints().maxOutgoing().getAsInt() == 1;
                spec.put("multiValued", !singleValued);
                spec.put("editable", "VALUE_TYPE".equals(col.rangeKind()) && !ctx.historical());
                columnSpecs.add(spec);
            }
        }

        List<Map<String, Object>> allRows = new ArrayList<>(instances.size());
        for (EntityNode node : instances) {
            Map<String, Object> row = isValueType
                    ? buildValueRow(reader, node)
                    : buildObjectRow(reader, node, columns);
            if (matchesFilters(row, filters)) {
                allRows.add(row);
            }
        }

        sortRows(allRows, sortBy, sortDir);

        int safeOffset = Math.max(0, offset);
        int safeLimit = limit > 0 ? limit : 200;
        int end = Math.min(allRows.size(), safeOffset + safeLimit);
        List<Map<String, Object>> pagedRows = safeOffset >= allRows.size()
                ? List.of()
                : allRows.subList(safeOffset, end);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("typeName", typeName);
        result.put("typeId", jsonId(typeId));
        result.put("typeKind", isValueType ? "VALUE_TYPE" : "OBJECT_TYPE");
        result.put("branch", ctx.branchName());
        result.put("commitId", ctx.commitId().toString());
        result.put("historical", ctx.historical());
        result.put("totalCount", instances.size());
        result.put("filteredCount", allRows.size());
        result.put("offset", safeOffset);
        result.put("limit", safeLimit);
        result.put("columns", columnSpecs);
        result.put("rows", pagedRows);
        return result;
    }

    public static Map<String, Object> entityDetail(VersionedGraphDatabase db, SnapshotContext ctx, EntityId entityId) {
        GraphReader reader = ctx.reader();
        EntityNode node = reader.findEntity(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Entity not found: " + entityId));

        String typeName = resolveTypeName(reader, node.typeId());
        List<Map<String, Object>> attributes = new ArrayList<>();
        List<Map<String, Object>> outgoingRelations = new ArrayList<>();

        for (Edge edge : reader.findOutgoingEdges(entityId)) {
            String predName = resolveTypeName(reader, edge.predicateId());
            EntityNode target = reader.findEntity(edge.targetId()).orElse(null);
            PredicateConstraints constraints = reader.resolvePredicateConstraints(edge.predicateId())
                    .orElse(PredicateConstraints.unconstrained());
            if (target != null && target.kind() == EntityKind.VALUE) {
                Map<String, Object> attr = new LinkedHashMap<>();
                attr.put("edgeId", jsonId(edge.id()));
                attr.put("predicateId", jsonId(edge.predicateId()));
                attr.put("predicate", predName);
                attr.put("valueEntityId", jsonId(target.id()));
                attr.put("valueType", resolveTypeName(reader, target.typeId()));
                attr.put("scalarType", target.payload().map(v -> v.valueType().name()).orElse("STRING"));
                attr.put("value", target.payload().map(GraphTabularService::formatValue).orElse(""));
                attr.put("mutability", reader.resolveValueMutability(target.id()).map(Enum::name).orElse("IMMUTABLE"));
                attr.put("maxOutgoing", constraints.maxOutgoing().isPresent() ? constraints.maxOutgoing().getAsInt() : null);
                attributes.add(attr);
            } else {
                outgoingRelations.add(edgeSummary(reader, edge, predName, target, true));
            }
        }

        List<Map<String, Object>> incomingRelations = new ArrayList<>();
        for (Edge edge : reader.findIncomingEdges(entityId)) {
            String predName = resolveTypeName(reader, edge.predicateId());
            EntityNode source = reader.findEntity(edge.sourceId()).orElse(null);
            incomingRelations.add(edgeSummary(reader, edge, predName, source, false));
        }

        List<Map<String, Object>> entityHistory = db.historyOf(entityId).stream()
                .map(c -> Map.<String, Object>of(
                        "commitId", c.id().toString(),
                        "timestamp", c.timestamp().toString(),
                        "branchName", c.branchName(),
                        "actor", c.actor().id(),
                        "reason", c.reason().text(),
                        "dataCategory", c.dataCategory().name(),
                        "changeCount", c.changes().size()
                ))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", jsonId(node.id()));
        result.put("kind", node.kind().name());
        result.put("typeId", jsonId(node.typeId()));
        result.put("typeName", typeName);
        result.put("label", resolveEntityLabel(reader, node));
        result.put("payload", node.payload().map(GraphTabularService::formatValue).orElse(null));
        result.put("scalarType", node.payload().map(v -> v.valueType().name()).orElse(null));
        result.put("mutability", node.kind() == EntityKind.VALUE
                ? reader.resolveValueMutability(node.id()).map(Enum::name).orElse("IMMUTABLE")
                : null);
        result.put("validityWindow", reader.resolveValidityWindow(node.id())
                .map(w -> Map.<String, Object>of(
                        "validFrom", w.validFrom() != null ? w.validFrom().toString() : "",
                        "validTo", w.validTo() != null ? w.validTo().toString() : ""
                ))
                .orElse(null));
        result.put("branch", ctx.branchName());
        result.put("commitId", ctx.commitId().toString());
        result.put("historical", ctx.historical());
        result.put("attributes", attributes);
        result.put("outgoingRelations", outgoingRelations);
        result.put("incomingRelations", incomingRelations);
        result.put("history", entityHistory);
        return result;
    }

    public static Map<String, Object> executeDslQuery(SnapshotContext ctx, String dsl) {
        GraphReader reader = ctx.reader();
        List<QueryRow> queryRows = reader.executeQuery(dsl);

        Set<String> aliasColumns = new LinkedHashSet<>();
        Set<String> projectedColumns = new LinkedHashSet<>();
        for (QueryRow row : queryRows) {
            aliasColumns.addAll(row.bindings().keySet());
            projectedColumns.addAll(row.projectedValues().keySet());
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        for (String alias : aliasColumns) {
            columns.add(Map.of("key", alias, "kind", "ENTITY_BINDING"));
        }
        for (String proj : projectedColumns) {
            columns.add(Map.of("key", proj, "kind", "PROJECTED_VALUE"));
        }

        List<Map<String, Object>> rows = new ArrayList<>(queryRows.size());
        for (QueryRow qr : queryRows) {
            Map<String, Object> bindingsJson = new LinkedHashMap<>();
            for (Map.Entry<String, EntityId> e : qr.bindings().entrySet()) {
                EntityNode n = reader.findEntity(e.getValue()).orElse(null);
                bindingsJson.put(e.getKey(), Map.of(
                        "id", jsonId(e.getValue()),
                        "typeName", n != null ? resolveTypeName(reader, n.typeId()) : "",
                        "label", n != null ? resolveEntityLabel(reader, n) : e.getValue().toString()
                ));
            }
            Map<String, Object> valuesJson = new LinkedHashMap<>();
            for (Map.Entry<String, Value> e : qr.projectedValues().entrySet()) {
                valuesJson.put(e.getKey(), formatValue(e.getValue()));
            }
            Map<String, Object> rowJson = new LinkedHashMap<>();
            rowJson.put("bindings", bindingsJson);
            rowJson.put("values", valuesJson);
            rows.add(rowJson);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", dsl);
        result.put("branch", ctx.branchName());
        result.put("commitId", ctx.commitId().toString());
        result.put("rowCount", rows.size());
        result.put("columns", columns);
        result.put("rows", rows);
        return result;
    }

    public static Map<String, Object> commitsHistory(VersionedGraphDatabase db) {
        List<Map<String, Object>> commits = db.commitHistory().stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.id().toString());
                    m.put("numericId", c.id().value());
                    m.put("parentIds", c.parentIds().stream().map(CommitId::toString).toList());
                    m.put("branchName", c.branchName());
                    m.put("dataCategory", c.dataCategory().name());
                    m.put("actor", c.actor().id());
                    m.put("actorDisplayName", c.actor().displayName());
                    m.put("reason", c.reason().text());
                    m.put("correlationId", c.correlationId().orElse(null));
                    m.put("timestamp", c.timestamp().toString());
                    m.put("changeCount", c.changes().size());
                    return m;
                })
                .toList();
        return Map.of(
                "currentBranch", db.currentBranch(),
                "headCommitId", db.headCommitId().toString(),
                "commits", commits
        );
    }

    public static Map<String, Object> diffCommits(VersionedGraphDatabase db, String fromStr, String toStr) {
        CommitId from = parseCommitId(fromStr);
        CommitId to = parseCommitId(toStr);
        GraphDiff diff = db.diff(from, to);
        GraphReader fromReader = db.asOf(from);
        GraphReader toReader = db.asOf(to);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fromCommitId", diff.fromCommitId().toString());
        out.put("toCommitId", diff.toCommitId().toString());
        out.put("empty", diff.isEmpty());
        out.put("addedEntities", diff.addedEntities().stream()
                .map(n -> Map.<String, Object>of(
                        "id", jsonId(n.id()),
                        "kind", n.kind().name(),
                        "typeName", resolveTypeName(toReader, n.typeId()),
                        "label", resolveEntityLabel(toReader, n),
                        "payload", n.payload().map(GraphTabularService::formatValue).orElse("")
                ))
                .toList());
        out.put("removedEntities", diff.removedEntities().stream()
                .map(n -> Map.<String, Object>of(
                        "id", jsonId(n.id()),
                        "kind", n.kind().name(),
                        "typeName", resolveTypeName(fromReader, n.typeId()),
                        "label", resolveEntityLabel(fromReader, n),
                        "payload", n.payload().map(GraphTabularService::formatValue).orElse("")
                ))
                .toList());
        out.put("updatedValues", diff.updatedValues().stream()
                .map(u -> Map.<String, Object>of(
                        "entityId", jsonId(u.entityId()),
                        "previousValue", formatValue(u.previousValue()),
                        "newValue", formatValue(u.newValue())
                ))
                .toList());
        out.put("addedEdges", diff.addedEdges().stream()
                .map(e -> Map.<String, Object>of(
                        "id", jsonId(e.id()),
                        "sourceId", jsonId(e.sourceId()),
                        "predicate", resolveTypeName(toReader, e.predicateId()),
                        "targetId", jsonId(e.targetId())
                ))
                .toList());
        out.put("removedEdges", diff.removedEdges().stream()
                .map(e -> Map.<String, Object>of(
                        "id", jsonId(e.id()),
                        "sourceId", jsonId(e.sourceId()),
                        "predicate", resolveTypeName(fromReader, e.predicateId()),
                        "targetId", jsonId(e.targetId())
                ))
                .toList());
        return out;
    }

    public static Map<String, Object> exportPatch(VersionedGraphDatabase db, String fromStr, String toStr) {
        CommitId from = parseCommitId(fromStr);
        CommitId to = parseCommitId(toStr);
        String patchText = VegPatchService.exportPatch(db, from, to);
        return Map.of(
                "fromCommitId", from.toString(),
                "toCommitId", to.toString(),
                "patchText", patchText
        );
    }

    public static Map<String, Object> applyOrMergePatch(
            VersionedGraphDatabase db,
            String patchText,
            String branch,
            PatchApplyMode mode,
            Actor actor,
            Reason reason
    ) {
        String targetBranch = resolveWritableBranch(db, branch);
        PatchResult res = VegPatchService.applyOrMergePatch(db, patchText, targetBranch, mode, actor, reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patchId", res.patchId());
        out.put("mode", res.mode().name());
        out.put("alreadyApplied", res.alreadyApplied());
        out.put("merged", res.merged());
        out.put("commitId", res.resultCommitId() != null ? res.resultCommitId().toString() : null);
        out.put("targetBranch", res.targetBranch());
        out.put("incomingBranch", res.incomingBranch());
        out.put("conflicts", res.conflicts().stream()
                .map(c -> Map.<String, Object>of(
                        "type", c.type().name(),
                        "entityId", jsonId(c.subjectId()),
                        "description", c.description()
                ))
                .toList());
        return out;
    }

    public static Map<String, Object> createObject(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            String typeName,
            Map<String, String> attributes
    ) {
        return createObject(db, branch, actor, reason, typeName, attributes, null);
    }

    public static Map<String, Object> createObject(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            String typeName,
            Map<String, String> attributes,
            IdGenerationStrategy idStrategy
    ) {
        String targetBranch = resolveWritableBranch(db, branch);
        EntityId createdId;
        CommitId commitId;
        try (GraphTransaction tx = db.beginTransaction(targetBranch, actor, reason, null, DataCategory.REFERENCE)) {
            if (idStrategy != null) {
                tx.setIdGenerationStrategy(idStrategy);
            }
            EntityId typeId = tx.findTypeByName(typeName)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown ObjectType: " + typeName));
            createdId = tx.createObject(typeId);
            if (attributes != null) {
                for (Map.Entry<String, String> entry : attributes.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isBlank()) {
                        applyAttributeMutation(tx, createdId, entry.getKey(), entry.getValue(), false);
                    }
                }
            }
            commitId = tx.commit();
        }
        return Map.of(
                "commitId", commitId.toString(),
                "branch", targetBranch,
                "entityId", jsonId(createdId)
        );
    }

    public static Map<String, Object> setAttributeCell(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            EntityId entityId,
            String predicateName,
            String newValue,
            boolean clear
    ) {
        String targetBranch = resolveWritableBranch(db, branch);
        CommitId commitId;
        EntityId valueId;
        try (GraphTransaction tx = db.beginTransaction(targetBranch, actor, reason, null, DataCategory.REFERENCE)) {
            valueId = applyAttributeMutation(tx, entityId, predicateName, newValue, clear);
            commitId = tx.commit();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitId", commitId.toString());
        result.put("branch", targetBranch);
        result.put("entityId", jsonId(entityId));
        result.put("predicate", predicateName);
        result.put("valueEntityId", valueId != null ? jsonId(valueId) : null);
        return result;
    }

    public static Map<String, Object> updateValueNode(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            EntityId valueEntityId,
            String newValueText
    ) {
        String targetBranch = resolveWritableBranch(db, branch);
        CommitId commitId;
        try (GraphTransaction tx = db.beginTransaction(targetBranch, actor, reason, null, DataCategory.REFERENCE)) {
            EntityNode node = tx.findEntity(valueEntityId)
                    .orElseThrow(() -> new IllegalArgumentException("Value entity not found: " + valueEntityId));
            ValueType vt = node.payload()
                    .map(Value::valueType)
                    .orElseGet(() -> resolveValueTypeScalar(tx, node.typeId()).orElse(ValueType.STRING));
            tx.updateValue(valueEntityId, parseScalarValue(vt, newValueText));
            commitId = tx.commit();
        }
        return Map.of(
                "commitId", commitId.toString(),
                "branch", targetBranch,
                "entityId", jsonId(valueEntityId)
        );
    }

    public static Map<String, Object> connectEntities(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            EntityId sourceId,
            String predicateName,
            EntityId targetId
    ) {
        String targetBranch = resolveWritableBranch(db, branch);
        CommitId commitId;
        EntityId edgeId;
        try (GraphTransaction tx = db.beginTransaction(targetBranch, actor, reason, null, DataCategory.REFERENCE)) {
            EntityId predId = tx.findPredicateByName(predicateName)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown predicate: " + predicateName));
            edgeId = tx.connect(sourceId, predId, targetId);
            commitId = tx.commit();
        }
        return Map.of(
                "commitId", commitId.toString(),
                "branch", targetBranch,
                "edgeId", jsonId(edgeId)
        );
    }

    public static Map<String, Object> disconnectEdge(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            EntityId edgeId
    ) {
        String targetBranch = resolveWritableBranch(db, branch);
        CommitId commitId;
        try (GraphTransaction tx = db.beginTransaction(targetBranch, actor, reason, null, DataCategory.REFERENCE)) {
            tx.disconnect(edgeId);
            commitId = tx.commit();
        }
        return Map.of(
                "commitId", commitId.toString(),
                "branch", targetBranch,
                "disconnectedEdgeId", jsonId(edgeId)
        );
    }

    public static Map<String, Object> defineObjectType(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            String name
    ) {
        String targetBranch = resolveWritableBranch(db, branch);
        CommitId commitId;
        EntityId typeId;
        try (GraphTransaction tx = db.beginTransaction(targetBranch, actor, reason, null, DataCategory.SCHEMA)) {
            typeId = tx.defineObjectType(name.trim());
            commitId = tx.commit();
        }
        return Map.of("commitId", commitId.toString(), "branch", targetBranch, "typeId", jsonId(typeId), "name", name.trim());
    }

    public static Map<String, Object> defineValueType(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            String name,
            ValueType scalarType,
            boolean uniqueValues
    ) {
        String targetBranch = resolveWritableBranch(db, branch);
        CommitId commitId;
        EntityId typeId;
        try (GraphTransaction tx = db.beginTransaction(targetBranch, actor, reason, null, DataCategory.SCHEMA)) {
            typeId = tx.defineValueType(name.trim(), scalarType, uniqueValues);
            commitId = tx.commit();
        }
        return Map.of(
                "commitId", commitId.toString(),
                "branch", targetBranch,
                "typeId", jsonId(typeId),
                "name", name.trim(),
                "scalarType", scalarType.name()
        );
    }

    public static Map<String, Object> definePredicate(
            VersionedGraphDatabase db,
            String branch,
            Actor actor,
            Reason reason,
            String name,
            String domainName,
            String rangeName,
            OptionalInt maxOutgoing,
            OptionalInt maxIncoming,
            boolean uniqueTriple
    ) {
        String targetBranch = resolveWritableBranch(db, branch);
        CommitId commitId;
        EntityId predId;
        try (GraphTransaction tx = db.beginTransaction(targetBranch, actor, reason, null, DataCategory.SCHEMA)) {
            EntityId domainId = resolveSchemaEndpoint(tx, domainName);
            EntityId rangeId = resolveSchemaEndpoint(tx, rangeName);
            PredicateConstraints constraints = new PredicateConstraints(maxOutgoing, maxIncoming, uniqueTriple);
            predId = tx.definePredicate(name.trim(), domainId, rangeId, constraints);
            commitId = tx.commit();
        }
        return Map.of(
                "commitId", commitId.toString(),
                "branch", targetBranch,
                "predicateId", jsonId(predId),
                "name", name.trim()
        );
    }

    public static Value parseScalarValue(ValueType scalarType, String raw) {
        Objects.requireNonNull(raw, "value must not be null");
        return switch (scalarType) {
            case STRING -> Value.of(raw);
            case INT -> Value.of(Long.parseLong(raw.trim()));
            case DECIMAL -> Value.of(new BigDecimal(raw.trim()));
            case BOOLEAN -> {
                String norm = raw.trim().toLowerCase(Locale.ROOT);
                if (!"true".equals(norm) && !"false".equals(norm)) {
                    throw new IllegalArgumentException("Expected boolean ('true' or 'false'), got: " + raw);
                }
                yield Value.of(Boolean.parseBoolean(norm));
            }
            case TIMESTAMP -> Value.of(parseTimestamp(raw.trim()));
            case BINARY -> Value.of(Base64.getDecoder().decode(raw.trim()));
        };
    }

    public static String formatValue(Value v) {
        return switch (v) {
            case Value.StringValue(String s) -> s;
            case Value.IntValue(long n) -> Long.toString(n);
            case Value.DecimalValue(BigDecimal d) -> d.toPlainString();
            case Value.BooleanValue(boolean b) -> Boolean.toString(b);
            case Value.TimestampValue(Instant t) -> t.toString();
            case Value.BinaryValue(byte[] bytes) -> Base64.getEncoder().encodeToString(bytes);
        };
    }

    private static EntityId applyAttributeMutation(
            GraphTransaction tx,
            EntityId entityId,
            String predicateName,
            String rawValue,
            boolean clear
    ) {
        EntityId predId = tx.findPredicateByName(predicateName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown predicate: " + predicateName));
        EntityId rangeId = tx.findOutgoingEdges(predId, BootstrapIds.HAS_RANGE).stream()
                .findFirst()
                .map(Edge::targetId)
                .orElseThrow(() -> new IllegalStateException("Predicate has no HAS_RANGE: " + predicateName));
        ValueType scalarType = resolveValueTypeScalar(tx, rangeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Predicate '" + predicateName + "' does not target a ValueType; use connect/disconnect for object relations."
                ));

        boolean shouldClear = clear || rawValue == null
                || (scalarType != ValueType.STRING && rawValue.isBlank());

        List<Edge> existing = tx.findOutgoingEdges(entityId, predId);
        PredicateConstraints constraints = tx.resolvePredicateConstraints(predId)
                .orElse(PredicateConstraints.unconstrained());

        if (shouldClear || constraints.maxOutgoing().orElse(Integer.MAX_VALUE) == 1) {
            for (Edge oldEdge : existing) {
                tx.disconnect(oldEdge.id());
            }
        }
        if (shouldClear) {
            return null;
        }
        Value typedValue = parseScalarValue(scalarType, rawValue);
        EntityId valId = tx.createValue(rangeId, typedValue);
        tx.connect(entityId, predId, valId);
        return valId;
    }

    private static List<PredicateMeta> selectColumnsForObjectType(
            GraphReader reader,
            EntityId typeId,
            List<EntityNode> instances,
            List<PredicateMeta> allPreds,
            Map<String, PredicateMeta> predByName
    ) {
        Map<String, PredicateMeta> chosen = new LinkedHashMap<>();
        // First add predicates explicitly bound to this ObjectType
        for (PredicateMeta pm : allPreds) {
            if (pm.domainId().equals(typeId)) {
                chosen.put(pm.name(), pm);
            }
        }
        // Discover predicates used on instances (including ANY_OBJECT attributes/relations)
        Set<EntityId> sampleUsedPreds = new LinkedHashSet<>();
        int scanLimit = Math.min(instances.size(), 250);
        for (int i = 0; i < scanLimit; i++) {
            for (Edge e : reader.findOutgoingEdges(instances.get(i).id())) {
                sampleUsedPreds.add(e.predicateId());
            }
        }
        for (PredicateMeta pm : allPreds) {
            if (sampleUsedPreds.contains(pm.id())) {
                chosen.putIfAbsent(pm.name(), pm);
            }
        }
        // If empty table without explicit domain columns, expose ANY_OBJECT value attributes for "+ New Row"
        if (chosen.isEmpty()) {
            for (PredicateMeta pm : allPreds) {
                if (BootstrapIds.ANY_OBJECT.equals(pm.domainId()) && "VALUE_TYPE".equals(pm.rangeKind())) {
                    chosen.putIfAbsent(pm.name(), pm);
                }
            }
        }
        // Keep stable, readable MS-Access column order: id/title/name first, then scalar attributes, then relations
        List<PredicateMeta> ordered = new ArrayList<>();
        for (String preferred : LABEL_PREDICATES) {
            PredicateMeta pm = chosen.remove(preferred);
            if (pm != null) {
                ordered.add(pm);
            }
        }
        List<PredicateMeta> remainingAttrs = new ArrayList<>();
        List<PredicateMeta> remainingRels = new ArrayList<>();
        for (PredicateMeta pm : chosen.values()) {
            if ("VALUE_TYPE".equals(pm.rangeKind())) {
                remainingAttrs.add(pm);
            } else {
                remainingRels.add(pm);
            }
        }
        remainingAttrs.sort(Comparator.comparing(PredicateMeta::name));
        remainingRels.sort(Comparator.comparing(PredicateMeta::name));
        ordered.addAll(remainingAttrs);
        ordered.addAll(remainingRels);
        return ordered;
    }

    private static Map<String, Object> buildObjectRow(
            GraphReader reader,
            EntityNode node,
            List<PredicateMeta> columns
    ) {
        Map<EntityId, List<Edge>> byPredId = new HashMap<>();
        for (Edge edge : reader.findOutgoingEdges(node.id())) {
            byPredId.computeIfAbsent(edge.predicateId(), k -> new ArrayList<>()).add(edge);
        }

        Map<String, Object> cells = new LinkedHashMap<>();
        Map<String, Object> cellMeta = new LinkedHashMap<>();
        for (PredicateMeta col : columns) {
            List<Edge> edges = byPredId.getOrDefault(col.id(), List.of());
            if ("VALUE_TYPE".equals(col.rangeKind())) {
                if (edges.isEmpty()) {
                    cells.put(col.name(), "");
                } else if (edges.size() == 1) {
                    Edge e = edges.getFirst();
                    String valStr = reader.findEntity(e.targetId())
                            .flatMap(EntityNode::payload)
                            .map(GraphTabularService::formatValue)
                            .orElse("");
                    cells.put(col.name(), valStr);
                    cellMeta.put(col.name(), Map.of(
                            "edgeId", jsonId(e.id()),
                            "valueEntityId", jsonId(e.targetId())
                    ));
                } else {
                    List<String> parts = new ArrayList<>(edges.size());
                    for (Edge e : edges) {
                        reader.findEntity(e.targetId())
                                .flatMap(EntityNode::payload)
                                .map(GraphTabularService::formatValue)
                                .ifPresent(parts::add);
                    }
                    cells.put(col.name(), String.join(", ", parts));
                }
            } else {
                List<Map<String, Object>> links = new ArrayList<>(edges.size());
                List<String> labels = new ArrayList<>(edges.size());
                for (Edge e : edges) {
                    EntityNode target = reader.findEntity(e.targetId()).orElse(null);
                    String lbl = target != null ? resolveEntityLabel(reader, target) : e.targetId().toString();
                    labels.add(lbl);
                    links.add(Map.of(
                            "edgeId", jsonId(e.id()),
                            "targetId", jsonId(e.targetId()),
                            "targetLabel", lbl,
                            "targetType", target != null ? resolveTypeName(reader, target.typeId()) : ""
                    ));
                }
                cells.put(col.name(), String.join(", ", labels));
                if (!links.isEmpty()) {
                    cellMeta.put(col.name(), Map.of("links", links));
                }
            }
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_id", jsonId(node.id()));
        row.put("_type", resolveTypeName(reader, node.typeId()));
        row.put("_label", resolveEntityLabel(reader, node));
        row.put("_cells", cells);
        row.put("_meta", cellMeta);
        return row;
    }

    private static Map<String, Object> buildValueRow(GraphReader reader, EntityNode node) {
        String valStr = node.payload().map(GraphTabularService::formatValue).orElse("");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_id", jsonId(node.id()));
        row.put("_type", resolveTypeName(reader, node.typeId()));
        row.put("_label", valStr);
        row.put("_cells", Map.of("_value", valStr));
        row.put("_meta", Map.of(
                "mutability", reader.resolveValueMutability(node.id()).map(Enum::name).orElse("IMMUTABLE"),
                "incomingCount", reader.findIncomingEdges(node.id()).size()
        ));
        return row;
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesFilters(Map<String, Object> row, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        Map<String, Object> cells = (Map<String, Object>) row.get("_cells");
        String global = filters.get("_q");
        if (global != null && !global.isBlank()) {
            String needle = global.trim().toLowerCase(Locale.ROOT);
            boolean hit = String.valueOf(row.get("_id")).contains(needle)
                    || String.valueOf(row.get("_label")).toLowerCase(Locale.ROOT).contains(needle);
            if (!hit) {
                for (Object val : cells.values()) {
                    if (String.valueOf(val).toLowerCase(Locale.ROOT).contains(needle)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit) {
                return false;
            }
        }
        for (Map.Entry<String, String> f : filters.entrySet()) {
            String key = f.getKey();
            String val = f.getValue();
            if ("_q".equals(key) || val == null || val.isBlank()) {
                continue;
            }
            String needle = val.trim().toLowerCase(Locale.ROOT);
            String cellVal = "_id".equals(key)
                    ? String.valueOf(row.get("_id"))
                    : String.valueOf(cells.getOrDefault(key, ""));
            if (!cellVal.toLowerCase(Locale.ROOT).contains(needle)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void sortRows(List<Map<String, Object>> rows, String sortBy, String sortDir) {
        boolean desc = "desc".equalsIgnoreCase(sortDir);
        String col = (sortBy == null || sortBy.isBlank()) ? "_id" : sortBy.trim();
        Comparator<Map<String, Object>> cmp = (a, b) -> {
            if ("_id".equals(col)) {
                EntityId ea = EntityId.parse(String.valueOf(a.get("_id")));
                EntityId eb = EntityId.parse(String.valueOf(b.get("_id")));
                return ea.compareTo(eb);
            }
            Map<String, Object> ca = (Map<String, Object>) a.get("_cells");
            Map<String, Object> cb = (Map<String, Object>) b.get("_cells");
            String va = String.valueOf(ca.getOrDefault(col, ""));
            String vb = String.valueOf(cb.getOrDefault(col, ""));
            BigDecimal da = tryParseDecimal(va);
            BigDecimal db = tryParseDecimal(vb);
            if (da != null && db != null) {
                return da.compareTo(db);
            }
            return va.compareToIgnoreCase(vb);
        };
        rows.sort(desc ? cmp.reversed() : cmp);
    }

    private static BigDecimal tryParseDecimal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<PredicateMeta> readPredicates(GraphReader reader) {
        List<PredicateMeta> out = new ArrayList<>();
        for (EntityNode node : reader.findEntitiesByType(BootstrapIds.META_PREDICATE_TYPE)) {
            if (node.id().isBootstrap()) {
                continue;
            }
            String name = resolveTypeName(reader, node.id());
            EntityId domainId = reader.findOutgoingEdges(node.id(), BootstrapIds.HAS_DOMAIN).stream()
                    .findFirst().map(Edge::targetId).orElse(BootstrapIds.ANY_ENTITY);
            EntityId rangeId = reader.findOutgoingEdges(node.id(), BootstrapIds.HAS_RANGE).stream()
                    .findFirst().map(Edge::targetId).orElse(BootstrapIds.ANY_ENTITY);
            String domainName = resolveSchemaLabel(reader, domainId);
            String rangeName = resolveSchemaLabel(reader, rangeId);
            Optional<ValueType> scalar = resolveValueTypeScalar(reader, rangeId);
            String rangeKind = resolveRangeKind(reader, rangeId, scalar);
            PredicateConstraints constraints = reader.resolvePredicateConstraints(node.id())
                    .orElse(PredicateConstraints.unconstrained());
            out.add(new PredicateMeta(node.id(), name, domainId, domainName, rangeId, rangeName, rangeKind, scalar.orElse(null), constraints));
        }
        return out;
    }

    private static Map<String, Object> predicateToMap(PredicateMeta pm, long edgeCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", jsonId(pm.id()));
        row.put("name", pm.name());
        row.put("domainId", jsonId(pm.domainId()));
        row.put("domainName", pm.domainName());
        row.put("rangeId", jsonId(pm.rangeId()));
        row.put("rangeName", pm.rangeName());
        row.put("rangeKind", pm.rangeKind());
        row.put("scalarType", pm.scalarType() != null ? pm.scalarType().name() : null);
        row.put("maxOutgoing", pm.constraints().maxOutgoing().isPresent() ? pm.constraints().maxOutgoing().getAsInt() : null);
        row.put("maxIncoming", pm.constraints().maxIncoming().isPresent() ? pm.constraints().maxIncoming().getAsInt() : null);
        row.put("uniqueTriple", pm.constraints().uniqueTriple());
        row.put("count", edgeCount);
        return row;
    }

    private static Map<String, Object> edgeSummary(
            GraphReader reader,
            Edge edge,
            String predName,
            EntityNode otherNode,
            boolean outgoing
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edgeId", jsonId(edge.id()));
        m.put("predicateId", jsonId(edge.predicateId()));
        m.put("predicate", predName);
        EntityId otherId = outgoing ? edge.targetId() : edge.sourceId();
        m.put(outgoing ? "targetId" : "sourceId", jsonId(otherId));
        m.put("otherId", jsonId(otherId));
        m.put("otherKind", otherNode != null ? otherNode.kind().name() : "UNKNOWN");
        m.put("otherType", otherNode != null ? resolveTypeName(reader, otherNode.typeId()) : "UNKNOWN");
        m.put("otherLabel", otherNode != null ? resolveEntityLabel(reader, otherNode) : otherId.toString());
        if (otherNode != null && otherNode.payload().isPresent()) {
            m.put("otherPayload", formatValue(otherNode.payload().get()));
        }
        reader.resolveValidityWindow(edge.id()).ifPresent(w -> m.put("validityWindow", Map.of(
                "validFrom", w.validFrom() != null ? w.validFrom().toString() : "",
                "validTo", w.validTo() != null ? w.validTo().toString() : ""
        )));
        return m;
    }

    public static String resolveEntityLabel(GraphReader reader, EntityNode node) {
        if (node.kind() == EntityKind.VALUE) {
            return node.payload().map(GraphTabularService::formatValue).orElse(node.id().toString());
        }
        if (node.kind() == EntityKind.META_TYPE || node.kind() == EntityKind.SCALAR_TYPE) {
            return resolveSchemaLabel(reader, node.id());
        }
        if (node.kind() == EntityKind.EDGE) {
            Optional<Edge> edge = reader.findEdge(node.id());
            if (edge.isPresent()) {
                Edge e = edge.get();
                return e.sourceId() + " -[" + resolveTypeName(reader, e.predicateId()) + "]-> " + e.targetId();
            }
            return "Edge " + node.id();
        }
        Map<String, String> candidates = new HashMap<>();
        for (Edge e : reader.findOutgoingEdges(node.id())) {
            String pName = resolveTypeName(reader, e.predicateId());
            if (LABEL_PREDICATES.contains(pName)) {
                reader.findEntity(e.targetId())
                        .flatMap(EntityNode::payload)
                        .map(GraphTabularService::formatValue)
                        .ifPresent(v -> candidates.putIfAbsent(pName, v));
            }
        }
        String code = candidates.get("id");
        String title = candidates.getOrDefault("title",
                candidates.getOrDefault("name", candidates.get("term")));
        if (code != null && title != null && !code.equals(title)) {
            return code + " — " + title;
        }
        if (title != null) {
            return title;
        }
        if (code != null) {
            return code;
        }
        for (String pref : LABEL_PREDICATES) {
            if (candidates.containsKey(pref)) {
                return candidates.get(pref);
            }
        }
        return resolveTypeName(reader, node.typeId()) + " " + node.id();
    }

    private static String resolveRangeKind(GraphReader reader, EntityId rangeId, Optional<ValueType> scalar) {
        if (scalar.isPresent()) {
            return "VALUE_TYPE";
        }
        if (BootstrapIds.ANY_OBJECT.equals(rangeId)
                || BootstrapIds.ANY_VALUE.equals(rangeId)
                || BootstrapIds.ANY_EDGE.equals(rangeId)
                || BootstrapIds.ANY_ENTITY.equals(rangeId)) {
            return "WILDCARD";
        }
        Optional<EntityNode> n = reader.findEntity(rangeId);
        if (n.isPresent() && BootstrapIds.META_OBJECT_TYPE.equals(n.get().typeId())) {
            return "OBJECT_TYPE";
        }
        return "OTHER";
    }

    private static Optional<ValueType> resolveValueTypeScalar(GraphReader reader, EntityId rangeId) {
        Optional<ValueType> bootstrapScalar = resolveBootstrapScalar(rangeId);
        if (bootstrapScalar.isPresent()) {
            return bootstrapScalar;
        }
        for (Edge edge : reader.findOutgoingEdges(rangeId, BootstrapIds.HAS_SCALAR_TYPE)) {
            Optional<ValueType> vt = BootstrapIds.toValueType(edge.targetId());
            if (vt.isPresent()) {
                return vt;
            }
        }
        return Optional.empty();
    }

    private static Optional<ValueType> resolveBootstrapScalar(EntityId id) {
        Optional<ValueType> direct = BootstrapIds.toValueType(id);
        if (direct.isPresent()) {
            return direct;
        }
        if (BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE.equals(id)) {
            return Optional.of(ValueType.STRING);
        }
        if (BootstrapIds.SYSTEM_INT_VALUE_TYPE.equals(id)) {
            return Optional.of(ValueType.INT);
        }
        if (BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE.equals(id)) {
            return Optional.of(ValueType.BOOLEAN);
        }
        if (BootstrapIds.SYSTEM_TIMESTAMP_VALUE_TYPE.equals(id)) {
            return Optional.of(ValueType.TIMESTAMP);
        }
        return Optional.empty();
    }

    private static String resolveTypeName(GraphReader reader, EntityId id) {
        return reader.resolveName(id).orElseGet(() -> resolveSchemaLabel(reader, id));
    }

    private static String resolveSchemaLabel(GraphReader reader, EntityId id) {
        if (BootstrapIds.ANY_OBJECT.equals(id)) {
            return "ANY_OBJECT";
        }
        if (BootstrapIds.ANY_VALUE.equals(id)) {
            return "ANY_VALUE";
        }
        if (BootstrapIds.ANY_EDGE.equals(id)) {
            return "ANY_EDGE";
        }
        if (BootstrapIds.ANY_ENTITY.equals(id)) {
            return "ANY_ENTITY";
        }
        if (BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE.equals(id)) {
            return "SystemIdentifier";
        }
        if (BootstrapIds.SYSTEM_INT_VALUE_TYPE.equals(id)) {
            return "SystemInt";
        }
        if (BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE.equals(id)) {
            return "SystemBoolean";
        }
        if (BootstrapIds.SYSTEM_TIMESTAMP_VALUE_TYPE.equals(id)) {
            return "SystemTimestamp";
        }
        return reader.resolveName(id).orElse(id.toString());
    }

    private static EntityId resolveSchemaEndpoint(GraphReader reader, String nameOrWildcard) {
        String s = nameOrWildcard.trim();
        return switch (s) {
            case "ANY_OBJECT" -> BootstrapIds.ANY_OBJECT;
            case "ANY_VALUE" -> BootstrapIds.ANY_VALUE;
            case "ANY_EDGE" -> BootstrapIds.ANY_EDGE;
            case "ANY_ENTITY" -> BootstrapIds.ANY_ENTITY;
            case "STRING" -> BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE;
            case "INT" -> BootstrapIds.SYSTEM_INT_VALUE_TYPE;
            case "BOOLEAN" -> BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE;
            case "TIMESTAMP" -> BootstrapIds.SYSTEM_TIMESTAMP_VALUE_TYPE;
            default -> reader.findTypeByName(s)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown schema type or wildcard: " + s));
        };
    }

    private static Instant parseTimestamp(String text) {
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    private static String resolveWritableBranch(VersionedGraphDatabase db, String branch) {
        String target = (branch != null && !branch.isBlank()) ? branch.trim() : db.currentBranch();
        if (db.findBranch(target).isEmpty()) {
            throw new IllegalArgumentException("Unknown branch: " + target);
        }
        return target;
    }
}
