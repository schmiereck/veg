package io.veg.engine;

import io.veg.model.AccessDecision;
import io.veg.model.DerivedAssertion;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.Permission;
import io.veg.model.PredicateConstraints;
import io.veg.model.ValidityWindow;
import io.veg.model.ValueMutability;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only {@link GraphReader} projection filtering entities and edges against a business
 * {@code validTime} using their {@link ValidityWindow} ({@code V-14}).
 */
final class BitemporalGraphReader implements GraphReader {

    private final GraphReader delegate;
    private final Instant validTime;

    BitemporalGraphReader(GraphReader delegate, Instant validTime) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.validTime = Objects.requireNonNull(validTime, "validTime must not be null");
    }

    private boolean isEntityVisible(EntityId id) {
        if (id == null) {
            return false;
        }
        return delegate.findEntity(id).isPresent()
                && delegate.resolveValidityWindow(id).map(w -> w.isValidAt(validTime)).orElse(true);
    }

    private boolean isEdgeVisible(Edge edge) {
        return edge != null
                && isEntityVisible(edge.id())
                && isEntityVisible(edge.sourceId())
                && isEntityVisible(edge.targetId());
    }

    @Override
    public Optional<EntityNode> findEntity(EntityId id) {
        Objects.requireNonNull(id, "id must not be null");
        if (!isEntityVisible(id)) {
            return Optional.empty();
        }
        return delegate.findEntity(id);
    }

    @Override
    public Optional<Edge> findEdge(EntityId edgeId) {
        Objects.requireNonNull(edgeId, "edgeId must not be null");
        return delegate.findEdge(edgeId).filter(this::isEdgeVisible);
    }

    @Override
    public Optional<EntityId> findTypeByName(String name) {
        return delegate.findTypeByName(name);
    }

    @Override
    public Optional<EntityId> findPredicateByName(String name) {
        return delegate.findPredicateByName(name);
    }

    @Override
    public Optional<String> resolveName(EntityId schemaEntityId) {
        return delegate.resolveName(schemaEntityId);
    }

    @Override
    public Optional<ValueMutability> resolveValueMutability(EntityId valueEntityId) {
        return isEntityVisible(valueEntityId) ? delegate.resolveValueMutability(valueEntityId) : Optional.empty();
    }

    @Override
    public Optional<PredicateConstraints> resolvePredicateConstraints(EntityId predicateId) {
        return delegate.resolvePredicateConstraints(predicateId);
    }

    @Override
    public boolean isUniqueValueType(EntityId valueTypeId) {
        return delegate.isUniqueValueType(valueTypeId);
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (!isEntityVisible(sourceId)) {
            return List.of();
        }
        return delegate.findOutgoingEdges(sourceId).stream().filter(this::isEdgeVisible).toList();
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        if (!isEntityVisible(sourceId)) {
            return List.of();
        }
        return delegate.findOutgoingEdges(sourceId, predicateId).stream().filter(this::isEdgeVisible).toList();
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        if (!isEntityVisible(targetId)) {
            return List.of();
        }
        return delegate.findIncomingEdges(targetId).stream().filter(this::isEdgeVisible).toList();
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId, EntityId predicateId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        if (!isEntityVisible(targetId)) {
            return List.of();
        }
        return delegate.findIncomingEdges(targetId, predicateId).stream().filter(this::isEdgeVisible).toList();
    }

    @Override
    public List<EntityNode> findEntitiesByType(EntityId typeId) {
        Objects.requireNonNull(typeId, "typeId must not be null");
        return delegate.findEntitiesByType(typeId).stream()
                .filter(n -> isEntityVisible(n.id()))
                .toList();
    }

    @Override
    public long entityCount() {
        return allEntities().size();
    }

    @Override
    public long edgeCount() {
        return allEdges().size();
    }

    @Override
    public List<EntityNode> allEntities() {
        return delegate.allEntities().stream().filter(n -> isEntityVisible(n.id())).toList();
    }

    @Override
    public List<Edge> allEdges() {
        return delegate.allEdges().stream().filter(this::isEdgeVisible).toList();
    }

    @Override
    public Optional<EntityId> findPrincipalByName(String principalId) {
        return delegate.findPrincipalByName(principalId);
    }

    @Override
    public AccessDecision evaluateAccess(String principalId, Permission permission, EntityId targetId) {
        return delegate.evaluateAccess(principalId, permission, targetId);
    }

    @Override
    public List<DerivedAssertion> findDerivedOutgoingAssertions(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (!isEntityVisible(sourceId)) {
            return List.of();
        }
        return filterAssertions(delegate.findDerivedOutgoingAssertions(sourceId));
    }

    @Override
    public List<DerivedAssertion> findDerivedOutgoingAssertions(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        if (!isEntityVisible(sourceId)) {
            return List.of();
        }
        return filterAssertions(delegate.findDerivedOutgoingAssertions(sourceId, predicateId));
    }

    @Override
    public List<DerivedAssertion> findAllOutgoingAssertions(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (!isEntityVisible(sourceId)) {
            return List.of();
        }
        return filterAssertions(delegate.findAllOutgoingAssertions(sourceId));
    }

    @Override
    public List<DerivedAssertion> findAllOutgoingAssertions(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        if (!isEntityVisible(sourceId)) {
            return List.of();
        }
        return filterAssertions(delegate.findAllOutgoingAssertions(sourceId, predicateId));
    }

    @Override
    public Optional<ValidityWindow> resolveValidityWindow(EntityId entityOrEdgeId) {
        return isEntityVisible(entityOrEdgeId) ? delegate.resolveValidityWindow(entityOrEdgeId) : Optional.empty();
    }

    private List<DerivedAssertion> filterAssertions(List<DerivedAssertion> assertions) {
        return assertions.stream()
                .filter(a -> isEntityVisible(a.sourceId()) && isEntityVisible(a.targetId()))
                .filter(a -> a.backingEdgeId().map(id -> delegate.findEdge(id).map(this::isEdgeVisible).orElse(false)).orElse(true))
                .toList();
    }
}
