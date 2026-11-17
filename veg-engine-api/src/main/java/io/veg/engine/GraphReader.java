package io.veg.engine;

import io.veg.model.AccessDecision;
import io.veg.model.Actor;
import io.veg.model.BootstrapIds;
import io.veg.model.DerivedAssertion;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityNode;
import io.veg.model.Permission;
import io.veg.model.PredicateConstraints;
import io.veg.model.QueryRow;
import io.veg.model.RdfExportOptions;
import io.veg.model.RdfExportResult;
import io.veg.model.ValidityWindow;
import io.veg.model.Value;
import io.veg.model.ValueMutability;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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

    List<EntityNode> allEntities();

    List<Edge> allEdges();

    Optional<EntityId> findPrincipalByName(String principalId);

    AccessDecision evaluateAccess(String principalId, Permission permission, EntityId targetId);

    default AccessDecision evaluateAccess(Actor actor, Permission permission, EntityId targetId) {
        return evaluateAccess(Objects.requireNonNull(actor, "actor").id(), permission, targetId);
    }

    List<DerivedAssertion> findDerivedOutgoingAssertions(EntityId sourceId);

    List<DerivedAssertion> findDerivedOutgoingAssertions(EntityId sourceId, EntityId predicateId);

    List<DerivedAssertion> findAllOutgoingAssertions(EntityId sourceId);

    List<DerivedAssertion> findAllOutgoingAssertions(EntityId sourceId, EntityId predicateId);

    // --- Stage 6: Storage-independent Query API/DSL & RDF export (S-04, S-06, T18) ---

    default GraphQuery query() {
        return GraphQuery.against(this);
    }

    default List<QueryRow> executeQuery(String dsl) {
        return GraphQueryDsl.execute(this, dsl);
    }

    default RdfExportResult exportRdf(RdfExportOptions options) {
        return RdfGraphExporter.export(this, options);
    }

    default RdfExportResult exportTurtle() {
        return RdfGraphExporter.export(this, RdfExportOptions.turtleDefault());
    }

    // --- Stage 10: Automatic secondary type index & bitemporal validity views (P-05, V-14) ---

    default List<EntityNode> findEntitiesByType(EntityId typeId) {
        Objects.requireNonNull(typeId, "typeId must not be null");
        return allEntities().stream()
                .filter(n -> n.typeId().equals(typeId))
                .toList();
    }

    default Optional<ValidityWindow> resolveValidityWindow(EntityId entityOrEdgeId) {
        Objects.requireNonNull(entityOrEdgeId, "entityOrEdgeId must not be null");
        Instant from = resolveTemporalBound(entityOrEdgeId, BootstrapIds.VALID_FROM);
        Instant to = resolveTemporalBound(entityOrEdgeId, BootstrapIds.VALID_TO);
        if (from == null && to == null) {
            return Optional.empty();
        }
        return Optional.of(ValidityWindow.of(from, to));
    }

    private Instant resolveTemporalBound(EntityId targetId, EntityId boundPredicateId) {
        List<Edge> edges = findOutgoingEdges(targetId, boundPredicateId);
        if (edges.isEmpty()) {
            return null;
        }
        return findEntity(edges.getFirst().targetId())
                .flatMap(EntityNode::payload)
                .filter(v -> v instanceof Value.TimestampValue)
                .map(v -> ((Value.TimestampValue) v).value())
                .orElse(null);
    }

    default GraphReader validAt(Instant validTime) {
        return new BitemporalGraphReader(this, validTime);
    }
}
