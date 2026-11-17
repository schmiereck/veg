package io.veg.engine.memory;

import io.veg.engine.CardinalityViolationException;
import io.veg.engine.DomainRangeViolationException;
import io.veg.engine.EntityNotFoundException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.TypeMismatchException;
import io.veg.engine.UniquenessViolationException;
import io.veg.model.BootstrapIds;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.PredicateConstraints;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateless validation helpers checking schema, scalar typing, domain/range,
 * value uniqueness, and predicate cardinality against a {@link MutableGraphState}.
 */
final class GraphSchemaValidator {

    private GraphSchemaValidator() {
    }

    static String requireValidSchemaName(String name) {
        if (name == null || name.isBlank()) {
            throw new SchemaViolationException("Schema element name must not be null or blank");
        }
        return name;
    }

    static EntityNode requireEntityExists(MutableGraphState state, EntityId id, String role) {
        EntityNode node = state.entities.get(id);
        if (node == null) {
            throw new EntityNotFoundException("Missing entity for " + role + ": " + id);
        }
        return node;
    }

    static void requireSchemaTypeNode(EntityNode node, String role) {
        if (node.kind() != EntityKind.META_TYPE && node.kind() != EntityKind.SCALAR_TYPE) {
            throw new TypeMismatchException(
                    "Schema constraint " + role + " (" + node.id() + ") must reference a type or wildcard, was: " + node.kind());
        }
    }

    static ValueType resolveScalarTypeFromGraph(MutableGraphState state, EntityId valueTypeId) {
        EntityId scalarEntityId = resolveSingleSchemaTarget(state, valueTypeId, BootstrapIds.HAS_SCALAR_TYPE);
        return BootstrapIds.toValueType(scalarEntityId)
                .orElseThrow(() -> new SchemaViolationException(
                        "Invalid scalar type target " + scalarEntityId + " for value type " + valueTypeId));
    }

    static EntityId resolveSingleSchemaTarget(MutableGraphState state, EntityId sourceId, EntityId metaPredicateId) {
        List<Edge> schemaEdges = state.findOutgoingEdges(sourceId, metaPredicateId);
        if (schemaEdges.size() != 1) {
            throw new SchemaViolationException(
                    "Expected exactly one " + describeId(state, metaPredicateId) + " edge for "
                            + describeId(state, sourceId) + ", found: " + schemaEdges.size());
        }
        return schemaEdges.getFirst().targetId();
    }

    static void validateConnection(
            MutableGraphState state,
            EntityNode sourceNode,
            EntityNode predicateNode,
            EntityNode targetNode
    ) {
        if (!BootstrapIds.META_PREDICATE_TYPE.equals(predicateNode.typeId())) {
            throw new TypeMismatchException("Predicate " + predicateNode.id() + " is not of type META_PREDICATE_TYPE");
        }

        EntityId predicateId = predicateNode.id();
        EntityId domainConstraint = resolveSingleSchemaTarget(state, predicateId, BootstrapIds.HAS_DOMAIN);
        EntityId rangeConstraint = resolveSingleSchemaTarget(state, predicateId, BootstrapIds.HAS_RANGE);

        if (!matchesTypeConstraint(sourceNode, domainConstraint)) {
            throw new DomainRangeViolationException(
                    "Source entity " + sourceNode.id() + " (type " + describeNodeType(state, sourceNode)
                            + ") violates domain constraint " + describeId(state, domainConstraint)
                            + " of predicate " + describeId(state, predicateId));
        }

        if (!matchesTypeConstraint(targetNode, rangeConstraint)) {
            throw new DomainRangeViolationException(
                    "Target entity " + targetNode.id() + " (type " + describeNodeType(state, targetNode)
                            + ") violates range constraint " + describeId(state, rangeConstraint)
                            + " of predicate " + describeId(state, predicateId));
        }

        PredicateConstraints constraints = state.resolvePredicateConstraints(predicateId)
                .orElse(PredicateConstraints.unconstrained());

        List<Edge> existingOutgoing = state.findOutgoingEdges(sourceNode.id(), predicateId);
        if (constraints.uniqueTriple()) {
            for (Edge existing : existingOutgoing) {
                if (existing.targetId().equals(targetNode.id())) {
                    throw new UniquenessViolationException(
                            "Duplicate triple prohibited for predicate " + describeId(state, predicateId)
                                    + ": (" + sourceNode.id() + ", " + predicateId + ", " + targetNode.id() + ")");
                }
            }
        }

        if (constraints.maxOutgoing().isPresent() && existingOutgoing.size() >= constraints.maxOutgoing().getAsInt()) {
            throw new CardinalityViolationException(
                    "Max outgoing cardinality (" + constraints.maxOutgoing().getAsInt() + ") exceeded for "
                            + sourceNode.id() + " on predicate " + describeId(state, predicateId));
        }

        if (constraints.maxIncoming().isPresent()) {
            List<Edge> existingIncoming = state.findIncomingEdges(targetNode.id(), predicateId);
            if (existingIncoming.size() >= constraints.maxIncoming().getAsInt()) {
                throw new CardinalityViolationException(
                        "Max incoming cardinality (" + constraints.maxIncoming().getAsInt() + ") exceeded for "
                                + targetNode.id() + " on predicate " + describeId(state, predicateId));
            }
        }
    }

    static void validateExistingEdgesForConstraints(
            MutableGraphState state,
            EntityId predicateId,
            PredicateConstraints newConstraints
    ) {
        Map<EntityId, Integer> outgoingCounts = new HashMap<>();
        Map<EntityId, Integer> incomingCounts = new HashMap<>();
        record EndpointPair(EntityId source, EntityId target) {
        }
        Set<EndpointPair> seenPairs = new HashSet<>();

        for (Edge edge : state.edges.values()) {
            if (!edge.predicateId().equals(predicateId)) {
                continue;
            }
            if (newConstraints.uniqueTriple() && !seenPairs.add(new EndpointPair(edge.sourceId(), edge.targetId()))) {
                throw new UniquenessViolationException(
                        "Cannot enable uniqueTriple on predicate " + describeId(state, predicateId)
                                + ": existing duplicate edges between " + edge.sourceId() + " and " + edge.targetId());
            }
            int out = outgoingCounts.merge(edge.sourceId(), 1, Integer::sum);
            if (newConstraints.maxOutgoing().isPresent() && out > newConstraints.maxOutgoing().getAsInt()) {
                throw new CardinalityViolationException(
                        "Cannot tighten maxOutgoing to " + newConstraints.maxOutgoing().getAsInt()
                                + " on predicate " + describeId(state, predicateId)
                                + ": entity " + edge.sourceId() + " already has " + out + " outgoing edges");
            }
            int in = incomingCounts.merge(edge.targetId(), 1, Integer::sum);
            if (newConstraints.maxIncoming().isPresent() && in > newConstraints.maxIncoming().getAsInt()) {
                throw new CardinalityViolationException(
                        "Cannot tighten maxIncoming to " + newConstraints.maxIncoming().getAsInt()
                                + " on predicate " + describeId(state, predicateId)
                                + ": entity " + edge.targetId() + " already has " + in + " incoming edges");
            }
        }
    }

    static Map<Value, EntityId> buildUniqueValueIndexForExistingInstances(MutableGraphState state, EntityId valueTypeId) {
        Map<Value, EntityId> index = new HashMap<>();
        for (EntityNode node : state.entities.values()) {
            if (node.kind() == EntityKind.VALUE && node.typeId().equals(valueTypeId)) {
                Value payload = node.payload().orElseThrow();
                if (index.containsKey(payload)) {
                    throw new UniquenessViolationException(
                            "Cannot enable uniqueValues on " + describeId(state, valueTypeId)
                                    + ": duplicate value already exists: " + payload);
                }
                index.put(payload, node.id());
            }
        }
        return index;
    }

    static boolean isSchemaStructuralPredicate(EntityId predicateId) {
        return BootstrapIds.HAS_NAME.equals(predicateId)
                || BootstrapIds.HAS_SCALAR_TYPE.equals(predicateId)
                || BootstrapIds.HAS_DOMAIN.equals(predicateId)
                || BootstrapIds.HAS_RANGE.equals(predicateId)
                || BootstrapIds.HAS_MAX_OUTGOING.equals(predicateId)
                || BootstrapIds.HAS_MAX_INCOMING.equals(predicateId)
                || BootstrapIds.HAS_UNIQUE_TRIPLE.equals(predicateId)
                || BootstrapIds.HAS_UNIQUE_VALUES.equals(predicateId)
                || BootstrapIds.HAS_MUTABILITY.equals(predicateId)
                || BootstrapIds.ACL_PRINCIPAL.equals(predicateId)
                || BootstrapIds.ACL_TARGET.equals(predicateId)
                || BootstrapIds.ACL_EFFECT.equals(predicateId)
                || BootstrapIds.ACL_PERMISSION.equals(predicateId)
                || BootstrapIds.PROPAGATION_PREDICATE.equals(predicateId)
                || BootstrapIds.PROPAGATION_DIRECTION.equals(predicateId)
                || BootstrapIds.PROPAGATION_PERMISSION.equals(predicateId)
                || BootstrapIds.HAS_INVERSE_PREDICATE.equals(predicateId)
                || BootstrapIds.HAS_SUPER_PREDICATE.equals(predicateId);
    }

    static boolean matchesTypeConstraint(EntityNode node, EntityId constraintTypeId) {
        if (BootstrapIds.ANY_ENTITY.equals(constraintTypeId)) {
            return true;
        }
        if (BootstrapIds.ANY_OBJECT.equals(constraintTypeId)) {
            return node.kind() == EntityKind.OBJECT;
        }
        if (BootstrapIds.ANY_VALUE.equals(constraintTypeId)) {
            return node.kind() == EntityKind.VALUE;
        }
        if (BootstrapIds.ANY_EDGE.equals(constraintTypeId)) {
            return node.kind() == EntityKind.EDGE;
        }
        return node.typeId().equals(constraintTypeId);
    }

    static String describeId(MutableGraphState state, EntityId id) {
        return state.resolveName(id).map(n -> n + " " + id).orElse(id.toString());
    }

    private static String describeNodeType(MutableGraphState state, EntityNode node) {
        return state.resolveName(node.typeId()).orElse(node.typeId().toString()) + " (" + node.kind() + ")";
    }
}
