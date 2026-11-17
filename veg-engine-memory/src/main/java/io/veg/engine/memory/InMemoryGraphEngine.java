package io.veg.engine.memory;

import io.veg.engine.DomainRangeViolationException;
import io.veg.engine.DuplicateSchemaElementException;
import io.veg.engine.EntityNotFoundException;
import io.veg.engine.GraphSession;
import io.veg.engine.ReferentialIntegrityException;
import io.veg.engine.SchemaViolationException;
import io.veg.engine.TypeMismatchException;
import io.veg.model.BootstrapIds;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reference Engine A: straightforward in-memory graph engine backed by standard Java maps.
 *
 * <p>Optimized for semantic clarity and baseline measurement, not memory compactness.
 * All schema elements (object types, value types, predicates, domain/range constraints) are stored
 * as ordinary graph entities and edges atop the minimal {@link BootstrapIds} kernel.</p>
 */
public final class InMemoryGraphEngine implements GraphSession {

    private final Map<EntityId, EntityNode> entities = new HashMap<>();
    private final Map<EntityId, Edge> edges = new HashMap<>();
    private final Map<EntityId, List<Edge>> outgoingEdges = new HashMap<>();
    private final Map<EntityId, List<Edge>> incomingEdges = new HashMap<>();

    private final Map<String, EntityId> typeByName = new HashMap<>();
    private final Map<String, EntityId> predicateByName = new HashMap<>();

    private long nextUserEntityId = EntityId.USER_MIN_ID;
    private long nextBootstrapAuxId = 100L;

    public InMemoryGraphEngine() {
        bootstrapKernel();
    }

    private void bootstrapKernel() {
        // Meta-types
        registerBootstrapType(BootstrapIds.META_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.MetaType");
        registerBootstrapType(BootstrapIds.META_OBJECT_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.ObjectType");
        registerBootstrapType(BootstrapIds.META_VALUE_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.ValueType");
        registerBootstrapType(BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.PredicateType");
        registerBootstrapType(BootstrapIds.META_EDGE_TYPE, BootstrapIds.META_TYPE, EntityKind.META_TYPE, "veg.meta.EdgeType");
        registerBootstrapType(BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, BootstrapIds.META_VALUE_TYPE, EntityKind.META_TYPE, "veg.meta.Identifier");

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

        // Meta-predicates
        registerBootstrapPredicate(BootstrapIds.HAS_NAME, "veg.meta.hasName");
        registerBootstrapPredicate(BootstrapIds.HAS_SCALAR_TYPE, "veg.meta.hasScalarType");
        registerBootstrapPredicate(BootstrapIds.HAS_DOMAIN, "veg.meta.hasDomain");
        registerBootstrapPredicate(BootstrapIds.HAS_RANGE, "veg.meta.hasRange");

        // Attach scalar type edge for SYSTEM_IDENTIFIER_VALUE_TYPE
        insertEdgeInternal(allocateBootstrapAuxId(), BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE,
                BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.SCALAR_STRING);

        // Attach names and domain/range constraints to all bootstrap schema elements
        attachBootstrapName(BootstrapIds.META_TYPE, "veg.meta.MetaType");
        attachBootstrapName(BootstrapIds.META_OBJECT_TYPE, "veg.meta.ObjectType");
        attachBootstrapName(BootstrapIds.META_VALUE_TYPE, "veg.meta.ValueType");
        attachBootstrapName(BootstrapIds.META_PREDICATE_TYPE, "veg.meta.PredicateType");
        attachBootstrapName(BootstrapIds.META_EDGE_TYPE, "veg.meta.EdgeType");
        attachBootstrapName(BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, "veg.meta.Identifier");

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

        attachBootstrapName(BootstrapIds.HAS_NAME, "veg.meta.hasName");
        attachBootstrapName(BootstrapIds.HAS_SCALAR_TYPE, "veg.meta.hasScalarType");
        attachBootstrapName(BootstrapIds.HAS_DOMAIN, "veg.meta.hasDomain");
        attachBootstrapName(BootstrapIds.HAS_RANGE, "veg.meta.hasRange");

        attachBootstrapDomainRange(BootstrapIds.HAS_NAME, BootstrapIds.ANY_ENTITY, BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE);
        attachBootstrapDomainRange(BootstrapIds.HAS_SCALAR_TYPE, BootstrapIds.META_VALUE_TYPE, BootstrapIds.META_TYPE);
        attachBootstrapDomainRange(BootstrapIds.HAS_DOMAIN, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.ANY_ENTITY);
        attachBootstrapDomainRange(BootstrapIds.HAS_RANGE, BootstrapIds.META_PREDICATE_TYPE, BootstrapIds.ANY_ENTITY);
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
        EntityId nameValueId = allocateBootstrapAuxId();
        entities.put(nameValueId, new EntityNode(
                nameValueId,
                BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE,
                EntityKind.VALUE,
                Optional.of(Value.of(name))
        ));
        insertEdgeInternal(allocateBootstrapAuxId(), targetSchemaId, BootstrapIds.HAS_NAME, nameValueId);
    }

    private void attachBootstrapDomainRange(EntityId predicateId, EntityId domainTypeId, EntityId rangeTypeId) {
        insertEdgeInternal(allocateBootstrapAuxId(), predicateId, BootstrapIds.HAS_DOMAIN, domainTypeId);
        insertEdgeInternal(allocateBootstrapAuxId(), predicateId, BootstrapIds.HAS_RANGE, rangeTypeId);
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
        EntityId nameValueId = allocateUserId();
        EntityId nameEdgeId = allocateUserId();

        entities.put(typeId, new EntityNode(typeId, BootstrapIds.META_OBJECT_TYPE, EntityKind.META_TYPE, Optional.empty()));
        entities.put(nameValueId, new EntityNode(nameValueId, BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, EntityKind.VALUE, Optional.of(Value.of(validName))));
        insertEdgeInternal(nameEdgeId, typeId, BootstrapIds.HAS_NAME, nameValueId);
        typeByName.put(validName, typeId);
        return typeId;
    }

    @Override
    public EntityId defineValueType(String name, ValueType scalarType) {
        String validName = requireValidSchemaName(name);
        Objects.requireNonNull(scalarType, "scalarType must not be null");
        if (typeByName.containsKey(validName)) {
            throw new DuplicateSchemaElementException("Type already exists: " + validName);
        }

        EntityId scalarEntityId = BootstrapIds.forValueType(scalarType);
        EntityId typeId = allocateUserId();
        EntityId nameValueId = allocateUserId();
        EntityId nameEdgeId = allocateUserId();
        EntityId scalarEdgeId = allocateUserId();

        entities.put(typeId, new EntityNode(typeId, BootstrapIds.META_VALUE_TYPE, EntityKind.META_TYPE, Optional.empty()));
        entities.put(nameValueId, new EntityNode(nameValueId, BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, EntityKind.VALUE, Optional.of(Value.of(validName))));
        insertEdgeInternal(nameEdgeId, typeId, BootstrapIds.HAS_NAME, nameValueId);
        insertEdgeInternal(scalarEdgeId, typeId, BootstrapIds.HAS_SCALAR_TYPE, scalarEntityId);
        typeByName.put(validName, typeId);
        return typeId;
    }

    @Override
    public EntityId definePredicate(String name, EntityId domainTypeId, EntityId rangeTypeId) {
        String validName = requireValidSchemaName(name);
        Objects.requireNonNull(domainTypeId, "domainTypeId must not be null");
        Objects.requireNonNull(rangeTypeId, "rangeTypeId must not be null");
        if (predicateByName.containsKey(validName)) {
            throw new DuplicateSchemaElementException("Predicate already exists: " + validName);
        }

        EntityNode domainNode = requireEntityExists(domainTypeId, "domainTypeId");
        EntityNode rangeNode = requireEntityExists(rangeTypeId, "rangeTypeId");
        requireSchemaTypeNode(domainNode, "domainTypeId");
        requireSchemaTypeNode(rangeNode, "rangeTypeId");

        EntityId predicateId = allocateUserId();
        EntityId nameValueId = allocateUserId();
        EntityId nameEdgeId = allocateUserId();
        EntityId domainEdgeId = allocateUserId();
        EntityId rangeEdgeId = allocateUserId();

        entities.put(predicateId, new EntityNode(predicateId, BootstrapIds.META_PREDICATE_TYPE, EntityKind.META_TYPE, Optional.empty()));
        entities.put(nameValueId, new EntityNode(nameValueId, BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, EntityKind.VALUE, Optional.of(Value.of(validName))));
        insertEdgeInternal(nameEdgeId, predicateId, BootstrapIds.HAS_NAME, nameValueId);
        insertEdgeInternal(domainEdgeId, predicateId, BootstrapIds.HAS_DOMAIN, domainTypeId);
        insertEdgeInternal(rangeEdgeId, predicateId, BootstrapIds.HAS_RANGE, rangeTypeId);
        predicateByName.put(validName, predicateId);
        return predicateId;
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
    public EntityId createValue(EntityId valueTypeId, Value value) {
        Objects.requireNonNull(valueTypeId, "valueTypeId must not be null");
        Objects.requireNonNull(value, "value must not be null");

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

        EntityId id = allocateUserId();
        entities.put(id, new EntityNode(id, valueTypeId, EntityKind.VALUE, Optional.of(value)));
        return id;
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
                || BootstrapIds.HAS_RANGE.equals(predicateId);
    }

    private String describeNodeType(EntityNode node) {
        return resolveName(node.typeId()).orElse(node.typeId().toString()) + " (" + node.kind() + ")";
    }

    private String describeId(EntityId id) {
        return resolveName(id).map(n -> n + " " + id).orElse(id.toString());
    }
}
