package io.veg.engine.memory;

import io.veg.model.BootstrapIds;
import io.veg.model.Change;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.PredicateConstraints;
import io.veg.model.Value;
import io.veg.model.ValueMutability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Isolated mutable graph state backing {@link InMemoryGraphEngine}, {@link InMemoryGraphSnapshot},
 * savepoints, and deterministic commit replay.
 */
final class MutableGraphState {

    final Map<EntityId, EntityNode> entities;
    final Map<EntityId, Edge> edges;
    final Map<EntityId, List<Edge>> outgoingEdges;
    final Map<EntityId, List<Edge>> incomingEdges;

    final Map<String, EntityId> typeByName;
    final Map<String, EntityId> predicateByName;
    final Map<EntityId, Map<Value, EntityId>> uniqueValuesByType;

    long nextUserEntityId;
    long nextBootstrapAuxId;

    MutableGraphState() {
        this.entities = new HashMap<>();
        this.edges = new HashMap<>();
        this.outgoingEdges = new HashMap<>();
        this.incomingEdges = new HashMap<>();
        this.typeByName = new HashMap<>();
        this.predicateByName = new HashMap<>();
        this.uniqueValuesByType = new HashMap<>();
        this.nextUserEntityId = EntityId.USER_MIN_ID;
        this.nextBootstrapAuxId = 100L;
    }

    private MutableGraphState(MutableGraphState source) {
        this.entities = new HashMap<>(source.entities);
        this.edges = new HashMap<>(source.edges);
        this.outgoingEdges = copyAdjacency(source.outgoingEdges);
        this.incomingEdges = copyAdjacency(source.incomingEdges);
        this.typeByName = new HashMap<>(source.typeByName);
        this.predicateByName = new HashMap<>(source.predicateByName);
        this.uniqueValuesByType = new HashMap<>(source.uniqueValuesByType.size());
        for (var entry : source.uniqueValuesByType.entrySet()) {
            this.uniqueValuesByType.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        this.nextUserEntityId = source.nextUserEntityId;
        this.nextBootstrapAuxId = source.nextBootstrapAuxId;
    }

    MutableGraphState copy() {
        return new MutableGraphState(this);
    }

    EntityId allocateBootstrapAuxId() {
        long id = nextBootstrapAuxId++;
        if (id > EntityId.BOOTSTRAP_MAX_ID) {
            throw new IllegalStateException("Bootstrap auxiliary ID space exhausted");
        }
        return new EntityId(id);
    }

    EntityId allocateUserId() {
        return new EntityId(nextUserEntityId++);
    }

    void putEntity(EntityNode node) {
        entities.put(node.id(), node);
    }

    void putEdge(Edge edge) {
        entities.put(edge.id(), new EntityNode(edge.id(), BootstrapIds.META_EDGE_TYPE, EntityKind.EDGE, Optional.empty()));
        edges.put(edge.id(), edge);
        outgoingEdges.computeIfAbsent(edge.sourceId(), ignored -> new ArrayList<>()).add(edge);
        incomingEdges.computeIfAbsent(edge.targetId(), ignored -> new ArrayList<>()).add(edge);
    }

    Edge removeEdge(EntityId edgeId) {
        Edge removed = edges.remove(edgeId);
        if (removed == null) {
            return null;
        }
        entities.remove(edgeId);
        removeFromAdjacency(outgoingEdges, removed.sourceId(), edgeId);
        removeFromAdjacency(incomingEdges, removed.targetId(), edgeId);
        return removed;
    }

    void applyReplayChange(Change change) {
        switch (change) {
            case Change.EntityCreated(EntityNode node) -> entities.put(node.id(), node);
            case Change.EntityRemoved(EntityNode node) -> entities.remove(node.id());
            case Change.ValueUpdated(EntityId entityId, _, Value newValue) -> {
                EntityNode existing = entities.get(entityId);
                if (existing == null) {
                    throw new IllegalStateException("Missing entity during replay: " + entityId);
                }
                entities.put(entityId, new EntityNode(entityId, existing.typeId(), EntityKind.VALUE, Optional.of(newValue)));
            }
            case Change.EdgeConnected(Edge edge) -> putEdge(edge);
            case Change.EdgeDisconnected(Edge edge) -> removeEdge(edge.id());
        }
    }

    void rebuildDerivedIndexes() {
        outgoingEdges.clear();
        incomingEdges.clear();
        typeByName.clear();
        predicateByName.clear();
        uniqueValuesByType.clear();

        long maxUser = EntityId.USER_MIN_ID - 1;
        long maxBootstrapAux = 99L;

        List<Edge> sortedEdges = edges.values().stream()
                .sorted(Comparator.comparing(Edge::id))
                .toList();
        for (Edge edge : sortedEdges) {
            outgoingEdges.computeIfAbsent(edge.sourceId(), ignored -> new ArrayList<>()).add(edge);
            incomingEdges.computeIfAbsent(edge.targetId(), ignored -> new ArrayList<>()).add(edge);
        }

        List<EntityNode> sortedNodes = entities.values().stream()
                .sorted(Comparator.comparing(EntityNode::id))
                .toList();
        for (EntityNode node : sortedNodes) {
            long raw = node.id().value();
            if (raw >= EntityId.USER_MIN_ID) {
                maxUser = Math.max(maxUser, raw);
            } else if (raw >= 100L) {
                maxBootstrapAux = Math.max(maxBootstrapAux, raw);
            }

            if (node.kind() == EntityKind.META_TYPE || node.kind() == EntityKind.SCALAR_TYPE) {
                resolveName(node.id()).ifPresent(name -> {
                    if (BootstrapIds.META_PREDICATE_TYPE.equals(node.typeId())) {
                        predicateByName.put(name, node.id());
                    } else {
                        typeByName.put(name, node.id());
                    }
                });
                if (BootstrapIds.META_VALUE_TYPE.equals(node.typeId()) && isUniqueValueType(node.id())) {
                    uniqueValuesByType.computeIfAbsent(node.id(), ignored -> new HashMap<>());
                }
            }
        }

        for (EntityNode node : sortedNodes) {
            if (node.kind() == EntityKind.VALUE && uniqueValuesByType.containsKey(node.typeId())) {
                uniqueValuesByType.get(node.typeId()).put(node.payload().orElseThrow(), node.id());
            }
        }

        this.nextUserEntityId = maxUser + 1;
        this.nextBootstrapAuxId = maxBootstrapAux + 1;
    }

    Optional<String> resolveName(EntityId schemaEntityId) {
        List<Edge> nameEdges = findOutgoingEdges(schemaEntityId, BootstrapIds.HAS_NAME);
        if (nameEdges.isEmpty()) {
            return Optional.empty();
        }
        EntityNode nameValueNode = entities.get(nameEdges.getFirst().targetId());
        if (nameValueNode != null && nameValueNode.payload().isPresent()
                && nameValueNode.payload().get() instanceof Value.StringValue(String value)) {
            return Optional.of(value);
        }
        return Optional.empty();
    }

    Optional<ValueMutability> resolveValueMutability(EntityId valueEntityId) {
        EntityNode node = entities.get(valueEntityId);
        if (node == null || node.kind() != EntityKind.VALUE) {
            return Optional.empty();
        }
        List<Edge> mutEdges = findOutgoingEdges(valueEntityId, BootstrapIds.HAS_MUTABILITY);
        if (mutEdges.isEmpty()) {
            return Optional.empty();
        }
        return BootstrapIds.toValueMutability(mutEdges.getFirst().targetId());
    }

    Optional<PredicateConstraints> resolvePredicateConstraints(EntityId predicateId) {
        EntityNode node = entities.get(predicateId);
        if (node == null || !BootstrapIds.META_PREDICATE_TYPE.equals(node.typeId())) {
            return Optional.empty();
        }
        OptionalInt maxOut = resolveOptionalIntFlag(predicateId, BootstrapIds.HAS_MAX_OUTGOING);
        OptionalInt maxIn = resolveOptionalIntFlag(predicateId, BootstrapIds.HAS_MAX_INCOMING);
        boolean uniqueTriple = resolveBooleanFlag(predicateId, BootstrapIds.HAS_UNIQUE_TRIPLE);
        return Optional.of(new PredicateConstraints(maxOut, maxIn, uniqueTriple));
    }

    boolean isUniqueValueType(EntityId valueTypeId) {
        EntityNode node = entities.get(valueTypeId);
        if (node == null || !BootstrapIds.META_VALUE_TYPE.equals(node.typeId())) {
            return false;
        }
        return resolveBooleanFlag(valueTypeId, BootstrapIds.HAS_UNIQUE_VALUES);
    }

    List<Edge> findOutgoingEdges(EntityId sourceId) {
        List<Edge> list = outgoingEdges.get(sourceId);
        return list == null ? List.of() : List.copyOf(list);
    }

    List<Edge> findOutgoingEdges(EntityId sourceId, EntityId predicateId) {
        return filterByPredicate(outgoingEdges.get(sourceId), predicateId);
    }

    List<Edge> findIncomingEdges(EntityId targetId) {
        List<Edge> list = incomingEdges.get(targetId);
        return list == null ? List.of() : List.copyOf(list);
    }

    List<Edge> findIncomingEdges(EntityId targetId, EntityId predicateId) {
        return filterByPredicate(incomingEdges.get(targetId), predicateId);
    }

    OptionalInt resolveOptionalIntFlag(EntityId sourceId, EntityId flagPredicateId) {
        List<Edge> flagEdges = findOutgoingEdges(sourceId, flagPredicateId);
        if (flagEdges.isEmpty()) {
            return OptionalInt.empty();
        }
        EntityNode flagNode = entities.get(flagEdges.getFirst().targetId());
        if (flagNode != null && flagNode.payload().isPresent()
                && flagNode.payload().get() instanceof Value.IntValue(long val)) {
            return OptionalInt.of(Math.toIntExact(val));
        }
        return OptionalInt.empty();
    }

    boolean resolveBooleanFlag(EntityId sourceId, EntityId flagPredicateId) {
        List<Edge> flagEdges = findOutgoingEdges(sourceId, flagPredicateId);
        if (flagEdges.isEmpty()) {
            return false;
        }
        EntityNode flagNode = entities.get(flagEdges.getFirst().targetId());
        return flagNode != null && flagNode.payload().isPresent()
                && flagNode.payload().get() instanceof Value.BooleanValue(boolean val) && val;
    }

    private static List<Edge> filterByPredicate(List<Edge> list, EntityId predicateId) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Edge> result = new ArrayList<>();
        for (Edge edge : list) {
            if (edge.predicateId().equals(predicateId)) {
                result.add(edge);
            }
        }
        return List.copyOf(result);
    }

    private static Map<EntityId, List<Edge>> copyAdjacency(Map<EntityId, List<Edge>> source) {
        Map<EntityId, List<Edge>> copy = new HashMap<>(source.size());
        for (var entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    private static void removeFromAdjacency(Map<EntityId, List<Edge>> map, EntityId key, EntityId edgeId) {
        List<Edge> list = map.get(key);
        if (list == null) {
            return;
        }
        list.removeIf(e -> e.id().equals(edgeId));
        if (list.isEmpty()) {
            map.remove(key);
        }
    }
}
