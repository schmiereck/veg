package io.veg.engine.memory;

import io.veg.engine.CardinalityViolationException;
import io.veg.engine.DomainRangeViolationException;
import io.veg.engine.DuplicateSchemaElementException;
import io.veg.engine.EntityNotFoundException;
import io.veg.engine.GraphSession;
import io.veg.engine.ImmutableValueModificationException;
import io.veg.engine.ReferentialIntegrityException;
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
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reference Engine A: straightforward in-memory graph engine backed by standard Java maps.
 *
 * <p>Optimized for semantic clarity and baseline measurement, not memory compactness.
 * All schema elements (object types, value types, predicates, domain/range/cardinality/uniqueness
 * constraints, and value mutability) are stored as ordinary graph entities and edges atop the
 * minimal {@link BootstrapIds} kernel.</p>
 */
public final class InMemoryGraphEngine implements GraphSession {

    private final Map<EntityId, EntityNode> entities = new HashMap<>();
    private final Map<EntityId, Edge> edges = new HashMap<>();
    private final Map<EntityId, List<Edge>> outgoingEdges = new HashMap<>();
    private final Map<EntityId, List<Edge>> incomingEdges = new HashMap<>();

    private final Map<String, EntityId> typeByName = new HashMap<>();
    private final Map<String, EntityId> predicateByName = new HashMap<>();
    private final Map<EntityId, Map<Value, EntityId>> uniqueValuesByType = new HashMap<>();

    private long nextUserEntityId = EntityId.USER_MIN_ID;
    private long nextBootstrapAuxId = 100L;

    public InMemoryGraphEngine() {
        bootstrapKernel();
    }

    private void bootstrapKernel() {
        // Meta-types and system value types
        registerBootstrapType(BootstrapIds.META_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.MetaType");
        registerBootstrapType(BootstrapIds.META_OBJECT_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.ObjectType");
        registerBootstrapType(BootstrapIds.META_VALUE_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.ValueType");
        registerBootstrapType(BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.PredicateType");
        registerBootstrapType(BootstrapIds.META_EDGE_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.EdgeType");
        registerBootstrapType(BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, BootstrapIds.META_VALUE_TYPE, EntityKind.META_TYPE, "veg.meta.Identifier");
        registerBootstrapType(BootstrapIds.SYSTEM_INT_VALUE_TYPE, BootstrapIds.META_VALUE_TYPE, EntityKind.META_TYPE, "veg.meta.Int");
        registerBootstrapType(BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE, BootstrapIds.META_VALUE_TYPE, EntityKind.META_TYPE, "veg.meta.Boolean");

        // Scalar primitive types
        registerBootstrapType(BootstrapIds.SCALAR_STRING, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.String");
        registerBootstrapType(BootstrapIds.SCALAR_INT, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.Int");
        registerBootstrapType(BootstrapIds.SCALAR_DECIMAL, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.Decimal");
        registerBootstrapType(BootstrapIds.SCALAR_BOOLEAN, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.Boolean");
        registerBootstrapType(BootstrapIds.SCALAR_BINARY, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.Binary");
        registerBootstrapType(BootstrapIds.SCALAR_TIMESTAMP, BootstrapIds.META_TYPE, EntityKind.SCALAR_TYPE, "veg.scalar.Timestamp");

        // Wildcard constraints
        registerBootstrapType(BootstrapIds.ANY_ENTITY, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.wildcard.AnyEntity");
        registerBootstrapType(BootstrapIds.ANY_OBJECT, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.wildcard.AnyObject");
        registerBootstrapType(BootstrapIds.ANY_VALUE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.wildcard.AnyValue");
        registerBootstrapType(BootstrapIds.ANY_EDGE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.wildcard.AnyEdge");

        // Mutability markers
        registerBootstrapType(BootstrapIds.MUTABILITY_IMMUTABLE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.mutability.Immutable");
        registerBootstrapType(BootstrapIds.MUTABILITY_SHARED_MUTABLE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.mutability.SharedMutable");

        // Meta-predicates
        registerBootstrapPredicate(BootstrapIds.HAS_NAME, "veg.meta.hasName");
        registerBootstrapPredicate(BootstrapIds.HAS_SCALAR_TYPE, "veg.meta.hasScalarType");
        registerBootstrapPredicate(BootstrapIds.HAS_DOMAIN, "veg.meta.hasDomain");
        registerBootstrapPredicate(BootstrapIds.HAS_RANGE, "veg.meta.hasRange");
        registerBootstrapPredicate(BootstrapIds.HAS_MAX_OUTGOING, "veg.meta.hasMaxOutgoing");
        registerBootstrapPredicate(BootstrapIds.HAS_MAX_INCOMING, "veg.meta.hasMaxIncoming");
        registerBootstrapPredicate(BootstrapIds.HAS_UNIQUE_TRIPLE, "veg.meta.hasUniqueTriple");
        registerBootstrapPredicate(BootstrapIds.HAS_UNIQUE_VALUES, "veg.meta.hasUniqueValues");
        registerBootstrapPredicate(BootstrapIds.HAS_MUTABILITY, "veg.meta.hasMutability");

        // System value type scalar & uniqueness edges
        insertEdgeInternal(allocateBootstrapAuxId(), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE,
                BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.SCALAR_STRING);
        insertEdgeInternal(allocateBootstrapAuxId(), BootstrapIds.SYSTEM_INT_VALUE_TYPE,
                BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.SCALAR_INT);
        insertEdgeInternal(allocateBootstrapAuxId(), BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE,
                BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.SCALAR_BOOLEAN);

        attachBootstrapBooleanFlag(BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, BootstrapIds.HAS_UNIQUE_VALUES, false);
        attachBootstrapBooleanFlag(BootstrapIds.SYSTEM_INT_VALUE_TYPE, BootstrapIds.HAS_UNIQUE_VALUES, false);
        attachBootstrapBooleanFlag(BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE, BootstrapIds.HAS_UNIQUE_VALUES, false);

        // Attach names to all bootstrap schema elements
        attachBootstrapName(BootstrapIds.META_TYPE, "veg.meta.MetaType");
        attachBootstrapName(BootstrapIds.META_OBJECT_TYPE, "veg.meta.ObjectType");
        attachBootstrapName(BootstrapIds.META_VALUE_TYPE, "veg.meta.ValueType");
        attachBootstrapName(BootstrapIds.META_PREDICATE_TYPE, "veg.meta.PredicateType");
        attachBootstrapName(BootstrapIds.META_EDGE_TYPE, "veg.meta.EdgeType");
        attachBootstrapName(BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, "veg.meta.Identifier");
        attachBootstrapName(BootstrapIds.SYSTEM_INT_VALUE_TYPE, "veg.meta.Int");
        attachBootstrapName(BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE, "veg.meta.Boolean");

        attachBootstrapName(BootstrapIds.SCALAR_STRING, "veg.scalar.String");
        attachBootstrapName(BootstrapIds.SCALAR_INT, "veg.scalar.Int");
        attachBootstrapName(BootstrapIds.SCALAR_DECIMAL, "veg.scalar.Decimal");
        attachBootstrapName(BootstrapIds.SCALAR_BOOLEAN, "veg.scalar.Boolean");
        attachBootstrapName(BootstrapIds.SCALAR_BINARY, "veg.scalar.Binary");
        attachBootstrapName(BootstrapIds.SCALAR_TIMESTAMP, "veg.scalar.Timestamp");

        attachBootstrapName(BootstrapIds.ANY_ENTITY, "veg.wildcard.AnyEntity");
        attachBootstrapName(BootstrapIds.ANY_OBJECT, "veg.wildcard.AnyObject");
        attachBootstrapName(BootstrapIds.ANY_VALUE, "veg.wildcard.AnyValue");
        attachBootstrapName(BootstrapIds.ANY_EDGE, "veg.wildcard.AnyEdge");

        attachBootstrapName(BootstrapIds.MUTABILITY_IMMUTABLE, "veg.mutability.Immutable");
        attachBootstrapName(BootstrapIds.MUTABILITY_SHARED_MUTABLE, "veg.mutability.SharedMutable");

        attachBootstrapName(BootstrapIds.HAS_NAME, "veg.meta.hasName");
        attachBootstrapName(BootstrapIds.HAS_SCALAR_TYPE, "veg.meta.hasScalarType");
        attachBootstrapName(BootstrapIds.HAS_DOMAIN, "veg.meta.hasDomain");
        attachBootstrapName(BootstrapIds.HAS_RANGE, "veg.meta.hasRange");
        attachBootstrapName(BootstrapIds.HAS_MAX_OUTGOING, "veg.meta.hasMaxOutgoing");
        attachBootstrapName(BootstrapIds.HAS_MAX_INCOMING, "veg.meta.hasMaxIncoming");
        attachBootstrapName(BootstrapIds.HAS_UNIQUE_TRIPLE, "veg.meta.hasUniqueTriple");
        attachBootstrapName(BootstrapIds.HAS_UNIQUE_VALUES, "veg.meta.hasUniqueValues");
        attachBootstrapName(BootstrapIds.HAS_MUTABILITY, "veg.meta.hasMutability");

        // Attach domain/range/uniqueness constraints to all meta-predicates
        attachBootstrapPredicateMeta(BootstrapIds.HAS_NAME, BootstrapIds.ANY_ENTITY, BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        attachBootstrapPredicateMeta(BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.META_VALUE_TYPE, BootstrapIds.META_TYPE);
        attachBootstrapPredicateMeta(BootstrapIds.HAS_DOMAIN, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.ANY_ENTITY);
        attachBootstrapPredicateMeta(BootstrapIds.HAS_RANGE, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.ANY_ENTITY);
        attachBootstrapPredicateMeta(BootstrapIds.HAS_MAX_OUTGOING, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.SYSTEM_INT_VALUE_TYPE);
        attachBootstrapPredicateMeta(BootstrapIds.HAS_MAX_INCOMING, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.SYSTEM_INT_VALUE_TYPE);
        attachBootstrapPredicateMeta(BootstrapIds.HAS_UNIQUE_TRIPLE, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE);
        attachBootstrapPredicateMeta(BootstrapIds.HAS_UNIQUE_VALUES, BootstrapIds.META_VALUE_TYPE, BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE);
        attachBootstrapPredicateMeta(BootstrapIds.HAS_MUTABILITY, BootstrapIds.ANY_VALUE, BootstrapIds.META_TYPE);
    }

    private void registerBootstrapType(EntityId id, EntityId metaTypeId, EntityKind kind, String name) {
        entities.put(id, new EntityNode(id, metaTypeId, kind, Optional.empty()));
        typeByName.put(name, id);
    }

    private void registerBootstrapPredicate(EntityId id, String name) {
        entities.put(id, new EntityNode(id, BootstrapIds.META_PREDICATE_TYPE, EntityKind.META_TYPE, Optional.empty()));
        predicateByName.put(name, id);
    }

    private void attachBootstrapName(EntityId targetSchemaId, String name) {
        EntityId nameValueId = insertSystemImmutableValue(
                allocateBootstrapAuxId(), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, Value.of(name), true);
        insertEdgeInternal(allocateBootstrapAuxId(), targetSchemaId, BootstrapIds.HAS_NAME, nameValueId);
    }

    private void attachBootstrapBooleanFlag(EntityId targetSchemaId, EntityId flagPredicateId, boolean value) {
        EntityId flagValueId = insertSystemImmutableValue(
                allocateBootstrapAuxId(), BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE, Value.of(value), true);
        insertEdgeInternal(allocateBootstrapAuxId(), targetSchemaId, flagPredicateId, flagValueId);
    }

    private void attachBootstrapPredicateMeta(EntityId predicateId, EntityId domainTypeId, EntityId rangeTypeId) {
        insertEdgeInternal(allocateBootstrapAuxId(), predicateId, BootstrapIds.HAS_DOMAIN, domainTypeId);
        insertEdgeInternal(allocateBootstrapAuxId(), predicateId, BootstrapIds.HAS_RANGE, rangeTypeId);
        attachBootstrapBooleanFlag(predicateId, BootstrapIds.HAS_UNIQUE_TRIPLE, false);
    }

    private EntityId insertSystemImmutableValue(EntityId id, EntityId systemValueTypeId, Value value, boolean bootstrap) {
        entities.put(id, new EntityNode(id, systemValueTypeId, EntityKind.VALUE, Optional.of(value)));
        EntityId mutEdgeId = bootstrap ? allocateBootstrapAuxId() : allocateUserId();
        insertEdgeInternal(mutEdgeId, id, BootstrapIds.HAS_MUTABILITY, BootstrapIds.MUTABILITY_IMMUTABLE);
        return id;
    }

    private EntityId allocateBootstrapAuxId() {
        long id = nextBootstrapAuxId++;
        if (id > EntityId.BOOTSTRAP_MAX_ID) {
            throw new IllegalStateException("Bootstrap auxiliary ID space exhausted");
        }
        return new EntityId(id);
    }

    private EntityId allocateUserId() {
        return new EntityId(nextUserEntityId++);
    }

    @Override
    public EntityId defineObjectType(String name) {
        String validName = requireValidSchemaName(name);
        if (typeByName.containsKey(validName)) {
            throw new DuplicateSchemaElementException("Type already exists: " + validName);
        }

        EntityId typeId = allocateUserId();
        entities.put(typeId, new EntityNode(typeId, BootstrapIds.META_OBJECT_TYPE, EntityKind.META_TYPE, Optional.empty()));
        attachSchemaName(typeId, validName);
        typeByName.put(validName, typeId);
        return typeId;
    }

    @Override
    public EntityId defineValueType(String name, ValueType scalarType, boolean uniqueValues) {
        String validName = requireValidSchemaName(name);
        Objects.requireNonNull(scalarType, "scalarType must not be null");
        if (typeByName.containsKey(validName)) {
            throw new DuplicateSchemaElementException("Type already exists: " + validName);
        }

        EntityId scalarEntityId = BootstrapIds.forValueType(scalarType);
        EntityId typeId = allocateUserId();

        entities.put(typeId, new EntityNode(typeId, BootstrapIds.META_VALUE_TYPE, EntityKind.META_TYPE, Optional.empty()));
        attachSchemaName(typeId, validName);
        insertEdgeInternal(allocateUserId(), typeId, BootstrapIds.HAS_SCALAR_TYPE, scalarEntityId);
        attachSystemMetadataFlag(typeId, BootstrapIds.HAS_UNIQUE_VALUES, Value.of(uniqueValues), BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE);

        if (uniqueValues) {
            uniqueValuesByType.put(typeId, new HashMap<>());
        }
        typeByName.put(validName, typeId);
        return typeId;
    }

    @Override
    public EntityId definePredicate(String name, EntityId domainTypeId, EntityId rangeTypeId, PredicateConstraints constraints) {
        String validName = requireValidSchemaName(name);
        Objects.requireNonNull(domainTypeId, "domainTypeId must not be null");
        Objects.requireNonNull(rangeTypeId, "rangeTypeId must not be null");
        Objects.requireNonNull(constraints, "constraints must not be null");
        if (predicateByName.containsKey(validName)) {
            throw new DuplicateSchemaElementException("Predicate already exists: " + validName);
        }

        EntityNode domainNode = requireEntityExists(domainTypeId, "domainTypeId");
        EntityNode rangeNode = requireEntityExists(rangeTypeId, "rangeTypeId");
        requireSchemaTypeNode(domainNode, "domainTypeId");
        requireSchemaTypeNode(rangeNode, "rangeTypeId");

        EntityId predicateId = allocateUserId();
        entities.put(predicateId, new EntityNode(predicateId, BootstrapIds.META_PREDICATE_TYPE, EntityKind.META_TYPE, Optional.empty()));
        attachSchemaName(predicateId, validName);
        insertEdgeInternal(allocateUserId(), predicateId, BootstrapIds.HAS_DOMAIN, domainTypeId);
        insertEdgeInternal(allocateUserId(), predicateId, BootstrapIds.HAS_RANGE, rangeTypeId);

        if (constraints.maxOutgoing().isPresent()) {
            attachSystemMetadataFlag(predicateId, BootstrapIds.HAS_MAX_OUTGOING,
                    Value.of((long) constraints.maxOutgoing().getAsInt()), BootstrapIds.SYSTEM_INT_VALUE_TYPE);
        }
        if (constraints.maxIncoming().isPresent()) {
            attachSystemMetadataFlag(predicateId, BootstrapIds.HAS_MAX_INCOMING,
                    Value.of((long) constraints.maxIncoming().getAsInt()), BootstrapIds.SYSTEM_INT_VALUE_TYPE);
        }
        attachSystemMetadataFlag(predicateId, BootstrapIds.HAS_UNIQUE_TRIPLE,
                Value.of(constraints.uniqueTriple()), BootstrapIds.SYSTEM_BOOLEAN_VALUE_TYPE);

        predicateByName.put(validName, predicateId);
        return predicateId;
    }

    private void attachSchemaName(EntityId targetSchemaId, String name) {
        attachSystemMetadataFlag(targetSchemaId, BootstrapIds.HAS_NAME,
                Value.of(name), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
    }

    private void attachSystemMetadataFlag(EntityId targetSchemaId, EntityId predicateId, Value value, EntityId systemValueTypeId) {
        EntityId valueNodeId = insertSystemImmutableValue(allocateUserId(), systemValueTypeId, value, false);
        insertEdgeInternal(allocateUserId(), targetSchemaId, predicateId, valueNodeId);
    }

    @Override
    public EntityId createObject(EntityId objectTypeId) {
        Objects.requireNonNull(objectTypeId, "objectTypeId must not be null");
        EntityNode typeNode = requireEntityExists(objectTypeId, "objectTypeId");
        if (!BootstrapIds.META_OBJECT_TYPE.equals(typeNode.typeId())) {
            throw new TypeMismatchException("Expected an object type (META_OBJECT_TYPE), but " + objectTypeId + " has type " + typeNode.typeId());
        }

        EntityId id = allocateUserId();
        entities.put(id, new EntityNode(id, objectTypeId, EntityKind.OBJECT, Optional.empty()));
        return id;
    }

    @Override
    public EntityId createValue(EntityId valueTypeId, Value value, ValueMutability mutability) {
        Objects.requireNonNull(valueTypeId, "valueTypeId must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(mutability, "mutability must not be null");

        EntityNode typeNode = requireEntityExists(valueTypeId, "valueTypeId");
        if (!BootstrapIds.META_VALUE_TYPE.equals(typeNode.typeId())) {
            throw new TypeMismatchException("Expected a value type (META_VALUE_TYPE), but " + valueTypeId + " has type " + typeNode.typeId());
        }

        ValueType expectedScalarType = resolveScalarTypeFromGraph(valueTypeId);
        if (expectedScalarType != value.valueType()) {
            throw new TypeMismatchException(
                    "Type mismatch for value type " + resolveName(valueTypeId).orElse(valueTypeId.toString())
                            + ": expected " + expectedScalarType + " but got " + value.valueType());
        }

        if (isUniqueValueType(valueTypeId)) {
            Map<Value, EntityId> existingIndex = uniqueValuesByType.get(valueTypeId);
            if (existingIndex != null && existingIndex.containsKey(value)) {
                throw new UniquenessViolationException(
                        "Duplicate value for unique value type " + describeId(valueTypeId) + ": " + value);
            }
        }

        EntityId id = allocateUserId();
        entities.put(id, new EntityNode(id, valueTypeId, EntityKind.VALUE, Optional.of(value)));
        insertEdgeInternal(allocateUserId(), id, BootstrapIds.HAS_MUTABILITY, BootstrapIds.forValueMutability(mutability));

        if (isUniqueValueType(valueTypeId)) {
            uniqueValuesByType.computeIfAbsent(valueTypeId, ignored -> new HashMap<>()).put(value, id);
        }
        return id;
    }

    @Override
    public void updateValue(EntityId valueEntityId, Value newValue) {
        Objects.requireNonNull(valueEntityId, "valueEntityId must not be null");
        Objects.requireNonNull(newValue, "newValue must not be null");

        EntityNode existingNode = requireEntityExists(valueEntityId, "valueEntityId");
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

        ValueType expectedScalarType = resolveScalarTypeFromGraph(existingNode.typeId());
        if (expectedScalarType != newValue.valueType()) {
            throw new TypeMismatchException(
                    "Type mismatch for value entity " + valueEntityId + ": expected "
                            + expectedScalarType + " but got " + newValue.valueType());
        }

        boolean unique = isUniqueValueType(existingNode.typeId());
        if (unique) {
            Map<Value, EntityId> index = uniqueValuesByType.get(existingNode.typeId());
            if (index != null) {
                EntityId conflictingOwner = index.get(newValue);
                if (conflictingOwner != null && !conflictingOwner.equals(valueEntityId)) {
                    throw new UniquenessViolationException(
                            "Cannot update value entity " + valueEntityId + " to duplicate unique value " + newValue);
                }
            }
        }

        Value oldValue = existingNode.payload().orElseThrow();
        entities.put(valueEntityId, new EntityNode(valueEntityId, existingNode.typeId(), EntityKind.VALUE, Optional.of(newValue)));
        if (unique && !oldValue.equals(newValue)) {
            Map<Value, EntityId> index = uniqueValuesByType.computeIfAbsent(existingNode.typeId(), ignored -> new HashMap<>());
            index.remove(oldValue);
            index.put(newValue, valueEntityId);
        }
    }

    @Override
    public EntityId connect(EntityId sourceId, EntityId predicateId, EntityId targetId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");

        EntityNode sourceNode = requireEntityExists(sourceId, "sourceId");
        EntityNode predicateNode = requireEntityExists(predicateId, "predicateId");
        EntityNode targetNode = requireEntityExists(targetId, "targetId");

        if (!BootstrapIds.META_PREDICATE_TYPE.equals(predicateNode.typeId())) {
            throw new TypeMismatchException("Predicate " + predicateId + " is not of type META_PREDICATE_TYPE");
        }

        EntityId domainConstraint = resolveSingleSchemaTarget(predicateId, BootstrapIds.HAS_DOMAIN);
        EntityId rangeConstraint = resolveSingleSchemaTarget(predicateId, BootstrapIds.HAS_RANGE);

        if (!matchesTypeConstraint(sourceNode, domainConstraint)) {
            throw new DomainRangeViolationException(
                    "Source entity " + sourceId + " (type " + describeNodeType(sourceNode)
                            + ") violates domain constraint " + describeId(domainConstraint)
                            + " of predicate " + describeId(predicateId));
        }

        if (!matchesTypeConstraint(targetNode, rangeConstraint)) {
            throw new DomainRangeViolationException(
                    "Target entity " + targetId + " (type " + describeNodeType(targetNode)
                            + ") violates range constraint " + describeId(rangeConstraint)
                            + " of predicate " + describeId(predicateId));
        }

        PredicateConstraints constraints = resolvePredicateConstraints(predicateId)
                .orElse(PredicateConstraints.unconstrained());

        List<Edge> existingOutgoing = findOutgoingEdges(sourceId, predicateId);
        if (constraints.uniqueTriple()) {
            for (Edge existing : existingOutgoing) {
                if (existing.targetId().equals(targetId)) {
                    throw new UniquenessViolationException(
                            "Duplicate triple prohibited for predicate " + describeId(predicateId)
                                    + ": (" + sourceId + ", " + predicateId + ", " + targetId + ")");
                }
            }
        }

        if (constraints.maxOutgoing().isPresent() && existingOutgoing.size() >= constraints.maxOutgoing().getAsInt()) {
            throw new CardinalityViolationException(
                    "Max outgoing cardinality (" + constraints.maxOutgoing().getAsInt() + ") exceeded for "
                            + sourceId + " on predicate " + describeId(predicateId));
        }

        if (constraints.maxIncoming().isPresent()) {
            List<Edge> existingIncoming = findIncomingEdges(targetId, predicateId);
            if (existingIncoming.size() >= constraints.maxIncoming().getAsInt()) {
                throw new CardinalityViolationException(
                        "Max incoming cardinality (" + constraints.maxIncoming().getAsInt() + ") exceeded for "
                                + targetId + " on predicate " + describeId(predicateId));
            }
        }

        EntityId edgeId = allocateUserId();
        insertEdgeInternal(edgeId, sourceId, predicateId, targetId);
        return edgeId;
    }

    @Override
    public void disconnect(EntityId edgeId) {
        Objects.requireNonNull(edgeId, "edgeId must not be null");
        Edge edge = edges.get(edgeId);
        if (edge == null) {
            if (entities.containsKey(edgeId)) {
                throw new TypeMismatchException("Entity " + edgeId + " exists, but is not an edge");
            }
            throw new EntityNotFoundException("Edge not found: " + edgeId);
        }

        if (edgeId.isBootstrap() || isSchemaStructuralPredicate(edge.predicateId())) {
            throw new SchemaViolationException("Cannot disconnect immutable bootstrap or schema edge: " + edgeId);
        }

        if (!findOutgoingEdges(edgeId).isEmpty() || !findIncomingEdges(edgeId).isEmpty()) {
            throw new ReferentialIntegrityException(
                    "Cannot disconnect edge " + edgeId + " because other edges still reference it");
        }

        edges.remove(edgeId);
        entities.remove(edgeId);
        removeFromAdjacency(outgoingEdges, edge.sourceId(), edgeId);
        removeFromAdjacency(incomingEdges, edge.targetId(), edgeId);
    }

    @Override
    public Optional<EntityNode> findEntity(EntityId id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(entities.get(id));
    }

    @Override
    public Optional<Edge> findEdge(EntityId edgeId) {
        Objects.requireNonNull(edgeId, "edgeId must not be null");
        return Optional.ofNullable(edges.get(edgeId));
    }

    @Override
    public Optional<EntityId> findTypeByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(typeByName.get(name));
    }

    @Override
    public Optional<EntityId> findPredicateByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(predicateByName.get(name));
    }

    @Override
    public Optional<String> resolveName(EntityId schemaEntityId) {
        Objects.requireNonNull(schemaEntityId, "schemaEntityId must not be null");
        List<Edge> nameEdges = findOutgoingEdges(schemaEntityId, BootstrapIds.HAS_NAME);
        if (nameEdges.isEmpty()) {
            return Optional.empty();
        }
        EntityNode nameValueNode = entities.get(nameEdges.getFirst().targetId());
        if (nameValueNode == null || nameValueNode.payload().isEmpty()) {
            return Optional.empty();
        }
        if (nameValueNode.payload().get() instanceof Value.StringValue(String value)) {
            return Optional.of(value);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ValueMutability> resolveValueMutability(EntityId valueEntityId) {
        Objects.requireNonNull(valueEntityId, "valueEntityId must not be null");
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

    @Override
    public Optional<PredicateConstraints> resolvePredicateConstraints(EntityId predicateId) {
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        EntityNode node = entities.get(predicateId);
        if (node == null || !BootstrapIds.META_PREDICATE_TYPE.equals(node.typeId())) {
            return Optional.empty();
        }
        OptionalInt maxOut = resolveOptionalIntFlag(predicateId, BootstrapIds.HAS_MAX_OUTGOING);
        OptionalInt maxIn = resolveOptionalIntFlag(predicateId, BootstrapIds.HAS_MAX_INCOMING);
        boolean uniqueTriple = resolveBooleanFlag(predicateId, BootstrapIds.HAS_UNIQUE_TRIPLE);
        return Optional.of(new PredicateConstraints(maxOut, maxIn, uniqueTriple));
    }

    @Override
    public boolean isUniqueValueType(EntityId valueTypeId) {
        Objects.requireNonNull(valueTypeId, "valueTypeId must not be null");
        EntityNode node = entities.get(valueTypeId);
        if (node == null || !BootstrapIds.META_VALUE_TYPE.equals(node.typeId())) {
            return false;
        }
        return resolveBooleanFlag(valueTypeId, BootstrapIds.HAS_UNIQUE_VALUES);
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        List<Edge> list = outgoingEdges.get(sourceId);
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        List<Edge> list = outgoingEdges.get(sourceId);
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

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        List<Edge> list = incomingEdges.get(targetId);
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId, EntityId predicateId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        List<Edge> list = incomingEdges.get(targetId);
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

    @Override
    public long entityCount() {
        return entities.size();
    }

    @Override
    public long edgeCount() {
        return edges.size();
    }

    private void insertEdgeInternal(EntityId edgeId, EntityId sourceId, EntityId predicateId, EntityId targetId) {
        Edge edge = new Edge(edgeId, sourceId, predicateId, targetId);
        entities.put(edgeId, new EntityNode(edgeId, BootstrapIds.META_EDGE_TYPE, EntityKind.EDGE, Optional.empty()));
        edges.put(edgeId, edge);
        outgoingEdges.computeIfAbsent(sourceId, ignored -> new ArrayList<>()).add(edge);
        incomingEdges.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(edge);
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

    private OptionalInt resolveOptionalIntFlag(EntityId sourceId, EntityId flagPredicateId) {
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

    private boolean resolveBooleanFlag(EntityId sourceId, EntityId flagPredicateId) {
        List<Edge> flagEdges = findOutgoingEdges(sourceId, flagPredicateId);
        if (flagEdges.isEmpty()) {
            return false;
        }
        EntityNode flagNode = entities.get(flagEdges.getFirst().targetId());
        return flagNode != null && flagNode.payload().isPresent()
                && flagNode.payload().get() instanceof Value.BooleanValue(boolean val) && val;
    }

    private String requireValidSchemaName(String name) {
        if (name == null || name.isBlank()) {
            throw new SchemaViolationException("Schema element name must not be null or blank");
        }
        return name;
    }

    private EntityNode requireEntityExists(EntityId id, String role) {
        EntityNode node = entities.get(id);
        if (node == null) {
            throw new EntityNotFoundException("Missing entity for " + role + ": " + id);
        }
        return node;
    }

    private void requireSchemaTypeNode(EntityNode node, String role) {
        if (node.kind() != EntityKind.META_TYPE && node.kind() != EntityKind.SCALAR_TYPE) {
            throw new TypeMismatchException(
                    "Schema constraint " + role + " (" + node.id() + ") must reference a type or wildcard, was: " + node.kind());
        }
    }

    private ValueType resolveScalarTypeFromGraph(EntityId valueTypeId) {
        EntityId scalarEntityId = resolveSingleSchemaTarget(valueTypeId, BootstrapIds.HAS_SCALAR_TYPE);
        return BootstrapIds.toValueType(scalarEntityId)
                .orElseThrow(() -> new SchemaViolationException(
                        "Invalid scalar type target " + scalarEntityId + " for value type " + valueTypeId));
    }

    private EntityId resolveSingleSchemaTarget(EntityId sourceId, EntityId metaPredicateId) {
        List<Edge> schemaEdges = findOutgoingEdges(sourceId, metaPredicateId);
        if (schemaEdges.size() != 1) {
            throw new SchemaViolationException(
                    "Expected exactly one " + describeId(metaPredicateId) + " edge for " + describeId(sourceId)
                            + ", found: " + schemaEdges.size());
        }
        return schemaEdges.getFirst().targetId();
    }

    private boolean matchesTypeConstraint(EntityNode node, EntityId constraintTypeId) {
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

    private static boolean isSchemaStructuralPredicate(EntityId predicateId) {
        return BootstrapIds.HAS_NAME.equals(predicateId)
                || BootstrapIds.HAS_SCALAR_TYPE.equals(predicateId)
                || BootstrapIds.HAS_DOMAIN.equals(predicateId)
                || BootstrapIds.HAS_RANGE.equals(predicateId)
                || BootstrapIds.HAS_MAX_OUTGOING.equals(predicateId)
                || BootstrapIds.HAS_MAX_INCOMING.equals(predicateId)
                || BootstrapIds.HAS_UNIQUE_TRIPLE.equals(predicateId)
                || BootstrapIds.HAS_UNIQUE_VALUES.equals(predicateId)
                || BootstrapIds.HAS_MUTABILITY.equals(predicateId);
    }

    private String describeNodeType(EntityNode node) {
        return resolveName(node.typeId()).orElse(node.typeId().toString()) + " (" + node.kind() + ")";
    }

    private String describeId(EntityId id) {
        return resolveName(id).map(n -> n + " " + id).orElse(id.toString());
    }
}
