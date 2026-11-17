package io.veg.engine;

import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.PredicateConstraints;
import io.veg.model.ValueMutability;

import java.util.List;
import java.util.Optional;

/**
 * Read-only view of a graph state.
 *
 * <p>Separated from {@link GraphSession} so that future stages can back this contract with immutable
 * historical snapshots ({@code asOf}) and branch heads without breaking callers.</p>
 */
public interface GraphReader {

    Optional<EntityNode> findEntity(EntityId id);

    Optional<Edge> findEdge(EntityId edgeId);

    Optional<EntityId> findTypeByName(String name);

    Optional<EntityId> findPredicateByName(String name);

    Optional<String> resolveName(EntityId schemaEntityId);

    Optional<ValueMutability> resolveValueMutability(EntityId valueEntityId);

    Optional<PredicateConstraints> resolvePredicateConstraints(EntityId predicateId);

    boolean isUniqueValueType(EntityId valueTypeId);

    List<Edge> findOutgoingEdges(EntityId sourceId);

    List<Edge> findOutgoingEdges(EntityId sourceId, EntityId predicateId);

    List<Edge> findIncomingEdges(EntityId targetId);

    List<Edge> findIncomingEdges(EntityId targetId, EntityId predicateId);

    long entityCount();

    long edgeCount();
}
