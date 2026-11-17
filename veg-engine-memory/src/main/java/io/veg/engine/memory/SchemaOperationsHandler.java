package io.veg.engine.memory;

import io.veg.engine.DuplicateSchemaElementException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.TypeMismatchException;
import io.veg.model.BootstrapIds;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.PredicateConstraints;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Handles schema definition and schema evolution operations as graph-stored metadata.
 */
final class SchemaOperationsHandler {

    private final RecordedMutationSink mutations;

    SchemaOperationsHandler(RecordedMutationSink mutations) {
        this.mutations = mutations;
    }

    EntityId defineObjectType(MutableGraphState state, String name) {
        String validName = GraphSchemaValidator.requireValidSchemaName(name);
        if (state.typeByName.containsKey(validName)) {
            throw new DuplicateSchemaElementException("Type already exists: " + validName);
        }

        EntityId typeId = state.allocateUserId();
        mutations.createEntity(state, new EntityNode(typeId, BootstrapIds.META_OBJECT_TYPE, EntityKind.META_TYPE, Optional.empty()));
        mutations.attachMetadataFlag(state, typeId, BootstrapIds.HAS_NAME, Value.of(validName), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        state.typeByName.put(validName, typeId);
        return typeId;
    }

    EntityId defineValueType(MutableGraphState state, String name, ValueType scalarType, boolean uniqueValues) {
        String validName = GraphSchemaValidator.requireValidSchemaName(name);
        Objects.requireNonNull(scalarType, "scalarType must not be null");
        if (state.typeByName.containsKey(validName)) {
            throw new DuplicateSchemaElementException("Type already exists: " + validName);
        }

        EntityId typeId = state.allocateUserId();
        mutations.createEntity(state, new EntityNode(typeId, BootstrapIds.META_VALUE_TYPE, EntityKind.META_TYPE, Optional.empty()));
        mutations.attachMetadataFlag(state, typeId, BootstrapIds.HAS_NAME, Value.of(validName), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        mutations.connectEdge(state, state.allocateUserId(), typeId, BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.forValueType(scalarType));
        mutations.attachMetadataFlag(state, typeId, BootstrapIds.HAS_UNIQUE_VALUES, Value.of(uniqueValues), BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE);

        if (uniqueValues) {
            state.uniqueValuesByType.put(typeId, new HashMap<>());
        }
        state.typeByName.put(validName, typeId);
        return typeId;
    }

    EntityId definePredicate(
            MutableGraphState state,
            String name,
            EntityId domainTypeId,
            EntityId rangeTypeId,
            PredicateConstraints constraints
    ) {
        String validName = GraphSchemaValidator.requireValidSchemaName(name);
        Objects.requireNonNull(domainTypeId, "domainTypeId must not be null");
        Objects.requireNonNull(rangeTypeId, "rangeTypeId must not be null");
        Objects.requireNonNull(constraints, "constraints must not be null");
        if (state.predicateByName.containsKey(validName)) {
            throw new DuplicateSchemaElementException("Predicate already exists: " + validName);
        }

        EntityNode domainNode = GraphSchemaValidator.requireEntityExists(state, domainTypeId, "domainTypeId");
        EntityNode rangeNode = GraphSchemaValidator.requireEntityExists(state, rangeTypeId, "rangeTypeId");
        GraphSchemaValidator.requireSchemaTypeNode(domainNode, "domainTypeId");
        GraphSchemaValidator.requireSchemaTypeNode(rangeNode, "rangeTypeId");

        EntityId predicateId = state.allocateUserId();
        mutations.createEntity(state, new EntityNode(predicateId, BootstrapIds.META_PREDICATE_TYPE, EntityKind.META_TYPE, Optional.empty()));
        mutations.attachMetadataFlag(state, predicateId, BootstrapIds.HAS_NAME, Value.of(validName), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        mutations.connectEdge(state, state.allocateUserId(), predicateId, BootstrapIds.HAS_DOMAIN, domainTypeId);
        mutations.connectEdge(state, state.allocateUserId(), predicateId, BootstrapIds.HAS_RANGE, rangeTypeId);

        if (constraints.maxOutgoing().isPresent()) {
            mutations.attachMetadataFlag(state, predicateId, BootstrapIds.HAS_MAX_OUTGOING,
                    Value.of((long) constraints.maxOutgoing().getAsInt()), BootstrapIds.SYSTEM_INT_VALUE_TYPE);
        }
        if (constraints.maxIncoming().isPresent()) {
            mutations.attachMetadataFlag(state, predicateId, BootstrapIds.HAS_MAX_INCOMING,
                    Value.of((long) constraints.maxIncoming().getAsInt()), BootstrapIds.SYSTEM_INT_VALUE_TYPE);
        }
        mutations.attachMetadataFlag(state, predicateId, BootstrapIds.HAS_UNIQUE_TRIPLE,
                Value.of(constraints.uniqueTriple()), BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE);

        state.predicateByName.put(validName, predicateId);
        return predicateId;
    }

    void renameSchemaElement(MutableGraphState state, EntityId schemaEntityId, String newName) {
        Objects.requireNonNull(schemaEntityId, "schemaEntityId must not be null");
        String validNewName = GraphSchemaValidator.requireValidSchemaName(newName);
        if (schemaEntityId.isBootstrap()) {
            throw new SchemaViolationException("Cannot rename bootstrap schema element: " + schemaEntityId);
        }

        EntityNode schemaNode = GraphSchemaValidator.requireEntityExists(state, schemaEntityId, "schemaEntityId");
        if (schemaNode.kind() != EntityKind.META_TYPE) {
            throw new TypeMismatchException("Only META_TYPE schema elements can be renamed, was: " + schemaNode.kind());
        }

        List<Edge> nameEdges = state.findOutgoingEdges(schemaEntityId, BootstrapIds.HAS_NAME);
        if (nameEdges.size() != 1) {
            throw new SchemaViolationException("Schema element " + schemaEntityId + " has no unique HAS_NAME edge");
        }

        EntityNode nameValueNode = state.entities.get(nameEdges.getFirst().targetId());
        String oldName = ((Value.StringValue) nameValueNode.payload().orElseThrow()).value();
        if (oldName.equals(validNewName)) {
            return;
        }

        boolean isPredicate = BootstrapIds.META_PREDICATE_TYPE.equals(schemaNode.typeId());
        Map<String, EntityId> nameMap = isPredicate ? state.predicateByName : state.typeByName;
        if (nameMap.containsKey(validNewName)) {
            throw new DuplicateSchemaElementException("Schema name already exists: " + validNewName);
        }

        mutations.updateValue(state, nameValueNode, Value.of(validNewName));
        nameMap.remove(oldName);
        nameMap.put(validNewName, schemaEntityId);
    }

    void updatePredicateConstraints(MutableGraphState state, EntityId predicateId, PredicateConstraints newConstraints) {
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        Objects.requireNonNull(newConstraints, "newConstraints must not be null");
        if (predicateId.isBootstrap()) {
            throw new SchemaViolationException("Cannot update constraints on bootstrap predicate: " + predicateId);
        }

        EntityNode predicateNode = GraphSchemaValidator.requireEntityExists(state, predicateId, "predicateId");
        if (!BootstrapIds.META_PREDICATE_TYPE.equals(predicateNode.typeId())) {
            throw new TypeMismatchException("Entity " + predicateId + " is not a predicate");
        }

        GraphSchemaValidator.validateExistingEdgesForConstraints(state, predicateId, newConstraints);

        List<Edge> uniqueTripleEdges = state.findOutgoingEdges(predicateId, BootstrapIds.HAS_UNIQUE_TRIPLE);
        if (uniqueTripleEdges.size() == 1) {
            EntityNode uniqueNode = state.entities.get(uniqueTripleEdges.getFirst().targetId());
            Value newFlag = Value.of(newConstraints.uniqueTriple());
            if (!uniqueNode.payload().orElseThrow().equals(newFlag)) {
                mutations.updateValue(state, uniqueNode, newFlag);
            }
        } else {
            mutations.attachMetadataFlag(state, predicateId, BootstrapIds.HAS_UNIQUE_TRIPLE,
                    Value.of(newConstraints.uniqueTriple()), BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE);
        }

        updateOptionalIntConstraint(state, predicateId, BootstrapIds.HAS_MAX_OUTGOING,
                newConstraints.maxOutgoing().isPresent() ? newConstraints.maxOutgoing().getAsInt() : null);
        updateOptionalIntConstraint(state, predicateId, BootstrapIds.HAS_MAX_INCOMING,
                newConstraints.maxIncoming().isPresent() ? newConstraints.maxIncoming().getAsInt() : null);
    }

    void enableUniqueValues(MutableGraphState state, EntityId valueTypeId) {
        Objects.requireNonNull(valueTypeId, "valueTypeId must not be null");
        if (valueTypeId.isBootstrap()) {
            throw new SchemaViolationException("Cannot modify bootstrap value type: " + valueTypeId);
        }

        EntityNode typeNode = GraphSchemaValidator.requireEntityExists(state, valueTypeId, "valueTypeId");
        if (!BootstrapIds.META_VALUE_TYPE.equals(typeNode.typeId())) {
            throw new TypeMismatchException("Entity " + valueTypeId + " is not a META_VALUE_TYPE");
        }
        if (state.isUniqueValueType(valueTypeId)) {
            return;
        }

        Map<Value, EntityId> index = GraphSchemaValidator.buildUniqueValueIndexForExistingInstances(state, valueTypeId);
        List<Edge> flagEdges = state.findOutgoingEdges(valueTypeId, BootstrapIds.HAS_UNIQUE_VALUES);
        if (flagEdges.size() == 1) {
            EntityNode flagNode = state.entities.get(flagEdges.getFirst().targetId());
            mutations.updateValue(state, flagNode, Value.of(true));
        } else {
            mutations.attachMetadataFlag(state, valueTypeId, BootstrapIds.HAS_UNIQUE_VALUES,
                    Value.of(true), BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE);
        }
        state.uniqueValuesByType.put(valueTypeId, index);
    }

    private void updateOptionalIntConstraint(MutableGraphState state, EntityId predicateId, EntityId metaPredicateId, Integer newLimit) {
        List<Edge> existing = state.findOutgoingEdges(predicateId, metaPredicateId);
        if (newLimit == null) {
            for (Edge flagEdge : existing) {
                EntityNode flagValueNode = state.entities.get(flagEdge.targetId());
                mutations.disconnectEdge(state, flagEdge.id());
                if (flagValueNode != null) {
                    for (Edge mutEdge : state.findOutgoingEdges(flagValueNode.id(), BootstrapIds.HAS_MUTABILITY)) {
                        mutations.disconnectEdge(state, mutEdge.id());
                    }
                    mutations.removeEntity(state, flagValueNode);
                }
            }
            return;
        }

        Value newVal = Value.of((long) newLimit);
        if (existing.size() == 1) {
            EntityNode flagValueNode = state.entities.get(existing.getFirst().targetId());
            if (!flagValueNode.payload().orElseThrow().equals(newVal)) {
                mutations.updateValue(state, flagValueNode, newVal);
            }
        } else {
            mutations.attachMetadataFlag(state, predicateId, metaPredicateId, newVal, BootstrapIds.SYSTEM_INT_VALUE_TYPE);
        }
    }
}
