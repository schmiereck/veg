package io.veg.engine.memory;

import io.veg.engine.EntityNotFoundException;
import io.veg.engine.GraphSession;
import io.veg.engine.ImmutableValueModificationException;
import io.veg.engine.ReferentialIntegrityException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.TypeMismatchException;
import io.veg.engine.UniquenessViolationException;
import io.veg.model.AccessEffect;
import io.veg.model.BootstrapIds;
import io.veg.model.Change;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.Permission;
import io.veg.model.PredicateConstraints;
import io.veg.model.PropagationDirection;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reference Engine A: concise {@link GraphSession} orchestrating schema operations,
 * instance validation, and optional {@link Change} recording for transactions.
 */
public final class InMemoryGraphEngine extends AbstractGraphReader implements GraphSession {

    private MutableGraphState state;
    private final RecordedMutationSink mutations;
    private final SchemaOperationsHandler schemaOps;
    private final AccessControlOperationsHandler aclOps;

    public InMemoryGraphEngine() {
        this(new MutableGraphState(), null);
        BootstrapKernelInitializer.initialize(this.state, null);
    }

    InMemoryGraphEngine(MutableGraphState initialState, List<Change> changeRecorder) {
        this.state = Objects.requireNonNull(initialState, "initialState must not be null");
        this.mutations = new RecordedMutationSink(changeRecorder);
        this.schemaOps = new SchemaOperationsHandler(mutations);
        this.aclOps = new AccessControlOperationsHandler(mutations);
    }

    @Override
    protected MutableGraphState state() {
        return state;
    }

    void restoreState(MutableGraphState restoredState) {
        this.state = Objects.requireNonNull(restoredState, "restoredState must not be null");
    }

    @Override
    public EntityId defineObjectType(String name) {
        return schemaOps.defineObjectType(state, name);
    }

    @Override
    public EntityId defineValueType(String name, ValueType scalarType, boolean uniqueValues) {
        return schemaOps.defineValueType(state, name, scalarType, uniqueValues);
    }

    @Override
    public EntityId definePredicate(String name, EntityId domainTypeId, EntityId rangeTypeId, PredicateConstraints constraints) {
        return schemaOps.definePredicate(state, name, domainTypeId, rangeTypeId, constraints);
    }

    @Override
    public void renameSchemaElement(EntityId schemaEntityId, String newName) {
        schemaOps.renameSchemaElement(state, schemaEntityId, newName);
    }

    @Override
    public void updatePredicateConstraints(EntityId predicateId, PredicateConstraints newConstraints) {
        schemaOps.updatePredicateConstraints(state, predicateId, newConstraints);
    }

    @Override
    public void enableUniqueValues(EntityId valueTypeId) {
        schemaOps.enableUniqueValues(state, valueTypeId);
    }

    @Override
    public EntityId definePrincipal(String principalId) {
        return aclOps.definePrincipal(state, principalId);
    }

    @Override
    public EntityId addGroupMember(EntityId memberPrincipalId, EntityId groupPrincipalId) {
        return aclOps.addGroupMember(state, memberPrincipalId, groupPrincipalId);
    }

    @Override
    public EntityId grantPermission(EntityId principalEntityId, Permission permission, EntityId targetId) {
        return aclOps.createAclEntry(state, principalEntityId, AccessEffect.ALLOW, permission, targetId);
    }

    @Override
    public EntityId denyPermission(EntityId principalEntityId, Permission permission, EntityId targetId) {
        return aclOps.createAclEntry(state, principalEntityId, AccessEffect.DENY, permission, targetId);
    }

    @Override
    public EntityId definePermissionPropagation(EntityId predicateId, PropagationDirection direction, Permission permission) {
        return aclOps.definePermissionPropagation(state, predicateId, direction, permission);
    }

    @Override
    public void defineInversePredicate(EntityId predicateId, EntityId inversePredicateId) {
        aclOps.defineInversePredicate(state, predicateId, inversePredicateId);
    }

    @Override
    public void defineSubPredicate(EntityId subPredicateId, EntityId superPredicateId) {
        aclOps.defineSubPredicate(state, subPredicateId, superPredicateId);
    }

    @Override
    public EntityId createObject(EntityId objectTypeId) {
        Objects.requireNonNull(objectTypeId, "objectTypeId must not be null");
        EntityNode typeNode = GraphSchemaValidator.requireEntityExists(state, objectTypeId, "objectTypeId");
        if (!BootstrapIds.META_OBJECT_TYPE.equals(typeNode.typeId())) {
            throw new TypeMismatchException("Expected an object type (META_OBJECT_TYPE), but " + objectTypeId + " has type " + typeNode.typeId());
        }

        EntityId id = state.allocateUserId();
        mutations.createEntity(state, new EntityNode(id, objectTypeId, EntityKind.OBJECT, Optional.empty()));
        return id;
    }

    @Override
    public EntityId createValue(EntityId valueTypeId, Value value, ValueMutability mutability) {
        Objects.requireNonNull(valueTypeId, "valueTypeId must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(mutability, "mutability must not be null");

        EntityNode typeNode = GraphSchemaValidator.requireEntityExists(state, valueTypeId, "valueTypeId");
        if (!BootstrapIds.META_VALUE_TYPE.equals(typeNode.typeId())) {
            throw new TypeMismatchException("Expected a value type (META_VALUE_TYPE), but " + valueTypeId + " has type " + typeNode.typeId());
        }

        ValueType expectedScalarType = GraphSchemaValidator.resolveScalarTypeFromGraph(state, valueTypeId);
        if (expectedScalarType != value.valueType()) {
            throw new TypeMismatchException(
                    "Type mismatch for value type " + resolveName(valueTypeId).orElse(valueTypeId.toString())
                            + ": expected " + expectedScalarType + " but got " + value.valueType());
        }

        if (state.isUniqueValueType(valueTypeId)) {
            Map<Value, EntityId> existingIndex = state.uniqueValuesByType.get(valueTypeId);
            if (existingIndex != null && existingIndex.containsKey(value)) {
                throw new UniquenessViolationException(
                        "Duplicate value for unique value type " + GraphSchemaValidator.describeId(state, valueTypeId) + ": " + value);
            }
        }

        EntityId id = state.allocateUserId();
        mutations.createEntity(state, new EntityNode(id, valueTypeId, EntityKind.VALUE, Optional.of(value)));
        mutations.connectEdge(state, state.allocateUserId(), id, BootstrapIds.HAS_MUTABILITY, BootstrapIds.forValueMutability(mutability));

        if (state.isUniqueValueType(valueTypeId)) {
            state.uniqueValuesByType.computeIfAbsent(valueTypeId, ignored -> new HashMap<>()).put(value, id);
        }
        return id;
    }

    @Override
    public void updateValue(EntityId valueEntityId, Value newValue) {
        Objects.requireNonNull(valueEntityId, "valueEntityId must not be null");
        Objects.requireNonNull(newValue, "newValue must not be null");

        EntityNode existingNode = GraphSchemaValidator.requireEntityExists(state, valueEntityId, "valueEntityId");
        if (existingNode.kind() != EntityKind.VALUE) {
            throw new TypeMismatchException("Entity " + valueEntityId + " is not a VALUE entity, was: " + existingNode.kind());
        }
        if (valueEntityId.isBootstrap() || existingNode.typeId().isBootstrap()) {
            throw new ImmutableValueModificationException("Cannot mutate internal bootstrap/schema value entity: " + valueEntityId);
        }

        ValueMutability mutability = resolveValueMutability(valueEntityId).orElse(ValueMutability.IMMUTABLE);
        if (mutability != ValueMutability.SHARED_MUTABLE) {
            throw new ImmutableValueModificationException(
                    "Cannot update immutable value entity " + valueEntityId + " (mutability=" + mutability + ")");
        }

        ValueType expectedScalarType = GraphSchemaValidator.resolveScalarTypeFromGraph(state, existingNode.typeId());
        if (expectedScalarType != newValue.valueType()) {
            throw new TypeMismatchException(
                    "Type mismatch for value entity " + valueEntityId + ": expected "
                            + expectedScalarType + " but got " + newValue.valueType());
        }

        boolean unique = state.isUniqueValueType(existingNode.typeId());
        if (unique) {
            Map<Value, EntityId> index = state.uniqueValuesByType.get(existingNode.typeId());
            if (index != null) {
                EntityId conflictingOwner = index.get(newValue);
                if (conflictingOwner != null && !conflictingOwner.equals(valueEntityId)) {
                    throw new UniquenessViolationException(
                            "Cannot update value entity " + valueEntityId + " to duplicate unique value " + newValue);
                }
            }
        }

        Value oldValue = existingNode.payload().orElseThrow();
        mutations.updateValue(state, existingNode, newValue);
        if (unique && !oldValue.equals(newValue)) {
            state.uniqueValuesByType.computeIfAbsent(existingNode.typeId(), ignored -> new HashMap<>()).remove(oldValue);
            state.uniqueValuesByType.get(existingNode.typeId()).put(newValue, valueEntityId);
        }
    }

    @Override
    public EntityId connect(EntityId sourceId, EntityId predicateId, EntityId targetId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");

        EntityNode sourceNode = GraphSchemaValidator.requireEntityExists(state, sourceId, "sourceId");
        EntityNode predicateNode = GraphSchemaValidator.requireEntityExists(state, predicateId, "predicateId");
        EntityNode targetNode = GraphSchemaValidator.requireEntityExists(state, targetId, "targetId");

        GraphSchemaValidator.validateConnection(state, sourceNode, predicateNode, targetNode);

        EntityId edgeId = state.allocateUserId();
        mutations.connectEdge(state, edgeId, sourceId, predicateId, targetId);
        return edgeId;
    }

    @Override
    public void disconnect(EntityId edgeId) {
        Objects.requireNonNull(edgeId, "edgeId must not be null");
        Edge edge = state.edges.get(edgeId);
        if (edge == null) {
            if (state.entities.containsKey(edgeId)) {
                throw new TypeMismatchException("Entity " + edgeId + " exists, but is not an edge");
            }
            throw new EntityNotFoundException("Edge not found: " + edgeId);
        }

        if (edgeId.isBootstrap() || GraphSchemaValidator.isSchemaStructuralPredicate(edge.predicateId())) {
            throw new SchemaViolationException("Cannot disconnect immutable bootstrap or schema edge: " + edgeId);
        }

        if (!findOutgoingEdges(edgeId).isEmpty() || !findIncomingEdges(edgeId).isEmpty()) {
            throw new ReferentialIntegrityException(
                    "Cannot disconnect edge " + edgeId + " because other edges still reference it");
        }

        mutations.disconnectEdge(state, edgeId);
    }
}
