package io.veg.engine;

import io.veg.model.ComparisonOperator;
import io.veg.model.DerivedAssertion;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.QueryRow;
import io.veg.model.SortDirection;
import io.veg.model.SortOrder;
import io.veg.model.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Storage-independent fluent graph query engine operating purely over {@link GraphReader}
 * (live snapshots, {@code branchSnapshot}, {@code asOf}, and {@link AuthorizedGraphReader}).
 */
public final class GraphQuery {

    private sealed interface QueryStep permits MatchTypeStep, MatchEntityStep, TraverseStep, AttributeFilterStep, ConnectedFilterStep {
    }

    private record MatchTypeStep(String alias, String objectTypeName) implements QueryStep {
    }

    private record MatchEntityStep(String alias, EntityId entityId) implements QueryStep {
    }

    private record TraverseStep(String fromAlias, String predicateName, String toAlias, String targetObjectTypeName) implements QueryStep {
    }

    private record AttributeFilterStep(String alias, String predicateName, Predicate<Value> matcher) implements QueryStep {
    }

    private record ConnectedFilterStep(String sourceAlias, String predicateName, String targetAlias) implements QueryStep {
    }

    private final GraphReader reader;
    private final List<QueryStep> steps = new ArrayList<>();
    private final List<String> projections = new ArrayList<>();
    private final List<SortOrder> sortOrders = new ArrayList<>();
    private boolean includeDerived;
    private int offset = 0;
    private int limit = -1;

    private GraphQuery(GraphReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
    }

    public static GraphQuery against(GraphReader reader) {
        return new GraphQuery(reader);
    }

    public GraphQuery match(String alias, String objectTypeName) {
        requireNonBlank(alias, "alias");
        requireNonBlank(objectTypeName, "objectTypeName");
        steps.add(new MatchTypeStep(alias, objectTypeName));
        return this;
    }

    public GraphQuery matchEntity(String alias, EntityId entityId) {
        requireNonBlank(alias, "alias");
        Objects.requireNonNull(entityId, "entityId must not be null");
        steps.add(new MatchEntityStep(alias, entityId));
        return this;
    }

    public GraphQuery includeDerived(boolean includeDerived) {
        this.includeDerived = includeDerived;
        return this;
    }

    public GraphQuery traverse(String fromAlias, String predicateName, String toAlias) {
        return traverse(fromAlias, predicateName, toAlias, null);
    }

    public GraphQuery traverse(String fromAlias, String predicateName, String toAlias, String targetObjectTypeName) {
        requireNonBlank(fromAlias, "fromAlias");
        requireNonBlank(predicateName, "predicateName");
        requireNonBlank(toAlias, "toAlias");
        if (targetObjectTypeName != null && targetObjectTypeName.isBlank()) {
            throw new IllegalArgumentException("targetObjectTypeName must not be blank");
        }
        steps.add(new TraverseStep(fromAlias, predicateName, toAlias, targetObjectTypeName));
        return this;
    }

    public GraphQuery whereAttribute(String alias, String predicateName, ComparisonOperator operator, Value expected) {
        requireNonBlank(alias, "alias");
        requireNonBlank(predicateName, "predicateName");
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(expected, "expected must not be null");
        steps.add(new AttributeFilterStep(alias, predicateName, actual -> operator.matches(actual, expected)));
        return this;
    }

    public GraphQuery whereAttribute(String alias, String predicateName, Predicate<Value> predicate) {
        requireNonBlank(alias, "alias");
        requireNonBlank(predicateName, "predicateName");
        Objects.requireNonNull(predicate, "predicate must not be null");
        steps.add(new AttributeFilterStep(alias, predicateName, predicate));
        return this;
    }

    public GraphQuery whereConnected(String sourceAlias, String predicateName, String targetAlias) {
        requireNonBlank(sourceAlias, "sourceAlias");
        requireNonBlank(predicateName, "predicateName");
        requireNonBlank(targetAlias, "targetAlias");
        steps.add(new ConnectedFilterStep(sourceAlias, predicateName, targetAlias));
        return this;
    }

    public GraphQuery select(String... projections) {
        Objects.requireNonNull(projections, "projections must not be null");
        for (String projection : projections) {
            requireNonBlank(projection, "projection");
            this.projections.add(projection.trim());
        }
        return this;
    }

    public GraphQuery orderBy(String key, SortDirection direction) {
        this.sortOrders.add(new SortOrder(key, direction));
        return this;
    }

    public GraphQuery orderBy(String alias, String predicateName, SortDirection direction) {
        requireNonBlank(alias, "alias");
        requireNonBlank(predicateName, "predicateName");
        return orderBy(alias + "." + predicateName, direction);
    }

    public GraphQuery offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        this.offset = offset;
        return this;
    }

    public GraphQuery limit(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
        this.limit = limit;
        return this;
    }

    public long count() {
        return toList().size();
    }

    public List<QueryRow> toList() {
        if (steps.isEmpty()) {
            throw new IllegalStateException("GraphQuery requires at least one match step");
        }

        List<Map<String, EntityId>> candidateBindings = new ArrayList<>();
        candidateBindings.add(new LinkedHashMap<>());

        for (QueryStep step : steps) {
            if (candidateBindings.isEmpty()) {
                return List.of();
            }
            candidateBindings = executeStep(candidateBindings, step);
        }

        Set<String> projectionKeys = new LinkedHashSet<>(projections);
        if (projectionKeys.isEmpty()) {
            for (SortOrder sort : sortOrders) {
                if (sort.key().contains(".")) {
                    projectionKeys.add(sort.key());
                }
            }
        }

        List<Map<String, EntityId>> sortedBindings = sortBindingsDeterministically(candidateBindings);

        int start = Math.min(offset, sortedBindings.size());
        int end = limit < 0 ? sortedBindings.size() : Math.min(start + limit, sortedBindings.size());

        List<QueryRow> rows = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            Map<String, EntityId> bindings = sortedBindings.get(i);
            Map<String, Value> projected = resolveProjectedValues(bindings, projectionKeys);
            rows.add(new QueryRow(bindings, projected));
        }
        return List.copyOf(rows);
    }

    private List<Map<String, EntityId>> executeStep(List<Map<String, EntityId>> currentRows, QueryStep step) {
        return switch (step) {
            case MatchTypeStep(String alias, String typeName) -> executeMatchType(currentRows, alias, typeName);
            case MatchEntityStep(String alias, EntityId entityId) -> executeMatchEntity(currentRows, alias, entityId);
            case TraverseStep(String fromAlias, String predicateName, String toAlias, String targetTypeName) ->
                    executeTraverse(currentRows, fromAlias, predicateName, toAlias, targetTypeName);
            case AttributeFilterStep(String alias, String predicateName, Predicate<Value> matcher) ->
                    executeAttributeFilter(currentRows, alias, predicateName, matcher);
            case ConnectedFilterStep(String sourceAlias, String predicateName, String targetAlias) ->
                    executeConnectedFilter(currentRows, sourceAlias, predicateName, targetAlias);
        };
    }

    private List<Map<String, EntityId>> executeMatchType(
            List<Map<String, EntityId>> currentRows,
            String alias,
            String objectTypeName
    ) {
        Optional<EntityId> typeIdOpt = reader.findTypeByName(objectTypeName);
        if (typeIdOpt.isEmpty()) {
            return List.of();
        }
        EntityId typeId = typeIdOpt.get();
        List<EntityId> matchingEntities = reader.findEntitiesByType(typeId).stream()
                .filter(n -> n.kind() == EntityKind.OBJECT)
                .map(EntityNode::id)
                .sorted()
                .toList();
        if (matchingEntities.isEmpty()) {
            return List.of();
        }

        List<Map<String, EntityId>> next = new ArrayList<>();
        for (Map<String, EntityId> row : currentRows) {
            EntityId existing = row.get(alias);
            if (existing != null) {
                if (matchingEntities.contains(existing)) {
                    next.add(row);
                }
            } else {
                for (EntityId entityId : matchingEntities) {
                    Map<String, EntityId> expanded = new LinkedHashMap<>(row);
                    expanded.put(alias, entityId);
                    next.add(expanded);
                }
            }
        }
        return next;
    }

    private List<Map<String, EntityId>> executeMatchEntity(
            List<Map<String, EntityId>> currentRows,
            String alias,
            EntityId entityId
    ) {
        if (reader.findEntity(entityId).isEmpty()) {
            return List.of();
        }
        List<Map<String, EntityId>> next = new ArrayList<>();
        for (Map<String, EntityId> row : currentRows) {
            EntityId existing = row.get(alias);
            if (existing != null) {
                if (existing.equals(entityId)) {
                    next.add(row);
                }
            } else {
                Map<String, EntityId> expanded = new LinkedHashMap<>(row);
                expanded.put(alias, entityId);
                next.add(expanded);
            }
        }
        return next;
    }

    private List<Map<String, EntityId>> executeTraverse(
            List<Map<String, EntityId>> currentRows,
            String fromAlias,
            String predicateName,
            String toAlias,
            String targetObjectTypeName
    ) {
        Optional<EntityId> predIdOpt = reader.findPredicateByName(predicateName);
        if (predIdOpt.isEmpty()) {
            return List.of();
        }
        EntityId predId = predIdOpt.get();

        EntityId targetTypeId = null;
        if (targetObjectTypeName != null) {
            Optional<EntityId> resolvedType = reader.findTypeByName(targetObjectTypeName);
            if (resolvedType.isEmpty()) {
                return List.of();
            }
            targetTypeId = resolvedType.get();
        }

        List<Map<String, EntityId>> next = new ArrayList<>();
        for (Map<String, EntityId> row : currentRows) {
            EntityId fromId = row.get(fromAlias);
            if (fromId == null) {
                throw new QuerySyntaxException("Unbound source alias in JOIN/traverse: " + fromAlias);
            }
            Set<EntityId> targets = resolveOutgoingTargets(fromId, predId);
            EntityId alreadyBoundTarget = row.get(toAlias);
            for (EntityId targetId : targets) {
                if (alreadyBoundTarget != null && !alreadyBoundTarget.equals(targetId)) {
                    continue;
                }
                Optional<EntityNode> targetNodeOpt = reader.findEntity(targetId);
                if (targetNodeOpt.isEmpty()) {
                    continue;
                }
                if (targetTypeId != null) {
                    EntityNode targetNode = targetNodeOpt.get();
                    if (targetNode.kind() != EntityKind.OBJECT || !targetNode.typeId().equals(targetTypeId)) {
                        continue;
                    }
                }
                if (alreadyBoundTarget != null) {
                    next.add(row);
                } else {
                    Map<String, EntityId> expanded = new LinkedHashMap<>(row);
                    expanded.put(toAlias, targetId);
                    next.add(expanded);
                }
            }
        }
        return next;
    }

    private List<Map<String, EntityId>> executeAttributeFilter(
            List<Map<String, EntityId>> currentRows,
            String alias,
            String predicateName,
            Predicate<Value> matcher
    ) {
        Optional<EntityId> predIdOpt = reader.findPredicateByName(predicateName);
        if (predIdOpt.isEmpty()) {
            return List.of();
        }
        EntityId predId = predIdOpt.get();

        List<Map<String, EntityId>> next = new ArrayList<>();
        for (Map<String, EntityId> row : currentRows) {
            EntityId sourceId = row.get(alias);
            if (sourceId == null) {
                throw new QuerySyntaxException("Unbound alias in WHERE clause: " + alias);
            }
            if (hasMatchingAttributeValue(sourceId, predId, matcher)) {
                next.add(row);
            }
        }
        return next;
    }

    private List<Map<String, EntityId>> executeConnectedFilter(
            List<Map<String, EntityId>> currentRows,
            String sourceAlias,
            String predicateName,
            String targetAlias
    ) {
        Optional<EntityId> predIdOpt = reader.findPredicateByName(predicateName);
        if (predIdOpt.isEmpty()) {
            return List.of();
        }
        EntityId predId = predIdOpt.get();

        List<Map<String, EntityId>> next = new ArrayList<>();
        for (Map<String, EntityId> row : currentRows) {
            EntityId srcId = row.get(sourceAlias);
            EntityId dstId = row.get(targetAlias);
            if (srcId == null || dstId == null) {
                throw new QuerySyntaxException("Unbound alias in edge predicate filter: " + sourceAlias + " -> " + targetAlias);
            }
            if (resolveOutgoingTargets(srcId, predId).contains(dstId)) {
                next.add(row);
            }
        }
        return next;
    }

    private Set<EntityId> resolveOutgoingTargets(EntityId sourceId, EntityId predicateId) {
        Set<EntityId> targets = new LinkedHashSet<>();
        if (includeDerived) {
            for (DerivedAssertion assertion : reader.findAllOutgoingAssertions(sourceId, predicateId)) {
                targets.add(assertion.targetId());
            }
        } else {
            for (Edge edge : reader.findOutgoingEdges(sourceId, predicateId)) {
                targets.add(edge.targetId());
            }
        }
        return targets;
    }

    private boolean hasMatchingAttributeValue(EntityId sourceId, EntityId predicateId, Predicate<Value> matcher) {
        for (EntityId targetId : resolveOutgoingTargets(sourceId, predicateId)) {
            Optional<EntityNode> targetNode = reader.findEntity(targetId);
            if (targetNode.isPresent() && targetNode.get().payload().isPresent()) {
                if (matcher.test(targetNode.get().payload().get())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Value> resolveProjectedValues(Map<String, EntityId> bindings, Set<String> keys) {
        Map<String, Value> result = new LinkedHashMap<>();
        for (String key : keys) {
            resolveAttributeKey(bindings, key).ifPresent(val -> result.put(key, val));
        }
        return result;
    }

    private Optional<Value> resolveAttributeKey(Map<String, EntityId> bindings, String key) {
        int dot = key.indexOf('.');
        if (dot <= 0 || dot == key.length() - 1) {
            return Optional.empty();
        }
        String alias = key.substring(0, dot);
        String predicateName = key.substring(dot + 1);
        EntityId sourceId = bindings.get(alias);
        if (sourceId == null) {
            return Optional.empty();
        }
        Optional<EntityId> predIdOpt = reader.findPredicateByName(predicateName);
        if (predIdOpt.isEmpty()) {
            return Optional.empty();
        }
        for (EntityId targetId : resolveOutgoingTargets(sourceId, predIdOpt.get())) {
            Optional<EntityNode> targetNode = reader.findEntity(targetId);
            if (targetNode.isPresent() && targetNode.get().payload().isPresent()) {
                return targetNode.get().payload();
            }
        }
        return Optional.empty();
    }

    private List<Map<String, EntityId>> sortBindingsDeterministically(List<Map<String, EntityId>> rows) {
        record SortableRow(Map<String, EntityId> bindings, List<Object> sortKeys) {
        }

        List<SortableRow> decorated = new ArrayList<>(rows.size());
        for (Map<String, EntityId> row : rows) {
            List<Object> keys = new ArrayList<>(sortOrders.size());
            for (SortOrder order : sortOrders) {
                if (order.key().contains(".")) {
                    keys.add(resolveAttributeKey(row, order.key()).orElse(null));
                } else {
                    keys.add(row.get(order.key()));
                }
            }
            decorated.add(new SortableRow(row, keys));
        }

        decorated.sort((a, b) -> {
            for (int i = 0; i < sortOrders.size(); i++) {
                SortOrder order = sortOrders.get(i);
                Object ka = a.sortKeys().get(i);
                Object kb = b.sortKeys().get(i);
                if (ka == null && kb == null) {
                    continue;
                }
                if (ka == null) {
                    return 1; // Missing values always sort last
                }
                if (kb == null) {
                    return -1;
                }
                int raw = (ka instanceof Value va && kb instanceof Value vb)
                        ? ComparisonOperator.compareValues(va, vb)
                        : ((EntityId) ka).compareTo((EntityId) kb);
                int cmp = order.direction() == SortDirection.DESC ? -raw : raw;
                if (cmp != 0) {
                    return cmp;
                }
            }
            return compareBindingsDeterministically(a.bindings(), b.bindings());
        });

        List<Map<String, EntityId>> sorted = new ArrayList<>(decorated.size());
        for (SortableRow item : decorated) {
            sorted.add(item.bindings());
        }
        return sorted;
    }

    private static int compareBindingsDeterministically(Map<String, EntityId> a, Map<String, EntityId> b) {
        List<String> aliases = a.keySet().stream().sorted().toList();
        for (String alias : aliases) {
            EntityId ida = a.get(alias);
            EntityId idb = b.get(alias);
            if (ida != null && idb != null) {
                int cmp = ida.compareTo(idb);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return 0;
    }

    private static void requireNonBlank(String value, String role) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(role + " must not be null or blank");
        }
    }
}
