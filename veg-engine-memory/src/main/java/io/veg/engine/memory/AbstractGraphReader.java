package io.veg.engine.memory;

import io.veg.engine.GraphReader;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.PredicateConstraints;
import io.veg.model.ValueMutability;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared {@link GraphReader} implementation backed by a {@link MutableGraphState}.
 */
abstract class AbstractGraphReader implements GraphReader {

    protected abstract MutableGraphState state();

    @Override
    public Optional<EntityNode> findEntity(EntityId id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(state().entities.get(id));
    }

    @Override
    public Optional<Edge> findEdge(EntityId edgeId) {
        Objects.requireNonNull(edgeId, "edgeId must not be null");
        return Optional.ofNullable(state().edges.get(edgeId));
    }

    @Override
    public Optional<EntityId> findTypeByName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(state().typeByName.get(name));
    }

    @Override
    public Optional<EntityId> findPredicateByName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(state().predicateByName.get(name));
    }

    @Override
    public Optional<String> resolveName(EntityId schemaEntityId) {
        Objects.requireNonNull(schemaEntityId, "schemaEntityId must not be null");
        return state().resolveName(schemaEntityId);
    }

    @Override
    public Optional<ValueMutability> resolveValueMutability(EntityId valueEntityId) {
        Objects.requireNonNull(valueEntityId, "valueEntityId must not be null");
        return state().resolveValueMutability(valueEntityId);
    }

    @Override
    public Optional<PredicateConstraints> resolvePredicateConstraints(EntityId predicateId) {
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return state().resolvePredicateConstraints(predicateId);
    }

    @Override
    public boolean isUniqueValueType(EntityId valueTypeId) {
        Objects.requireNonNull(valueTypeId, "valueTypeId must not be null");
        return state().isUniqueValueType(valueTypeId);
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        return state().findOutgoingEdges(sourceId);
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return state().findOutgoingEdges(sourceId, predicateId);
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        return state().findIncomingEdges(targetId);
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId, EntityId predicateId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return state().findIncomingEdges(targetId, predicateId);
    }

    @Override
    public long entityCount() {
        return state().entities.size();
    }

    @Override
    public long edgeCount() {
        return state().edges.size();
    }

    @Override
    public List<EntityNode> allEntities() {
        return state().entities.values().stream()
                .sorted(Comparator.comparing(EntityNode::id))
                .toList();
    }

    @Override
    public List<Edge> allEdges() {
        return state().edges.values().stream()
                .sorted(Comparator.comparing(Edge::id))
                .toList();
    }
}
