package io.veg.engine.memory;

import io.veg.engine.GraphReader;
import io.veg.model.AccessDecision;
import io.veg.model.BootstrapIds;
import io.veg.model.Change;
import io.veg.model.DerivedAssertion;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.Permission;
import io.veg.model.PredicateConstraints;
import io.veg.model.Value;
import io.veg.model.ValueMutability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Engine C immutable graph snapshot backed by structurally shared {@link PersistentLongMap} radix tries.
 *
 * <p>Each commit derives a new snapshot solely from its delta {@code List<Change>}, sharing all
 * unmodified trie nodes physically with the parent snapshot ({@code P-04}, {@code H3}).</p>
 */
public final class PersistentGraphSnapshot implements GraphReader, PropagationRuleSource {

    private static final PersistentGraphSnapshot EMPTY = new PersistentGraphSnapshot(
            PersistentLongMap.empty(),
            PersistentLongMap.empty(),
            PersistentLongMap.empty(),
            PersistentLongMap.empty(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of()
    );

    private final PersistentLongMap<EntityNode> entities;
    private final PersistentLongMap<Edge> edges;
    private final PersistentLongMap<List<Edge>> outgoingEdges;
    private final PersistentLongMap<List<Edge>> incomingEdges;
    private final Map<String, EntityId> typeByName;
    private final Map<String, EntityId> predicateByName;
    private final Map<String, EntityId> principalByName;
    private final List<EntityId> propagationRuleIds;

    private PersistentGraphSnapshot(
            PersistentLongMap<EntityNode> entities,
            PersistentLongMap<Edge> edges,
            PersistentLongMap<List<Edge>> outgoingEdges,
            PersistentLongMap<List<Edge>> incomingEdges,
            Map<String, EntityId> typeByName,
            Map<String, EntityId> predicateByName,
            Map<String, EntityId> principalByName,
            List<EntityId> propagationRuleIds
    ) {
        this.entities = entities;
        this.edges = edges;
        this.outgoingEdges = outgoingEdges;
        this.incomingEdges = incomingEdges;
        this.typeByName = typeByName;
        this.predicateByName = predicateByName;
        this.principalByName = principalByName;
        this.propagationRuleIds = propagationRuleIds;
    }

    static PersistentGraphSnapshot empty() {
        return EMPTY;
    }

    PersistentGraphSnapshot applyChanges(List<Change> changes, ValueInterner interner) {
        if (changes.isEmpty()) {
            return this;
        }

        PersistentLongMap<EntityNode> nextEntities = this.entities;
        PersistentLongMap<Edge> nextEdges = this.edges;
        PersistentLongMap<List<Edge>> nextOutgoing = this.outgoingEdges;
        PersistentLongMap<List<Edge>> nextIncoming = this.incomingEdges;

        Map<String, EntityId> nextTypes = null;
        Map<String, EntityId> nextPredicates = null;
        Map<String, EntityId> nextPrincipals = null;
        List<EntityId> nextPropRules = null;

        Set<EntityId> touchedNameSubjects = new LinkedHashSet<>();

        for (Change change : changes) {
            switch (change) {
                case Change.EntityCreated(EntityNode rawNode) -> {
                    EntityNode node = rawNode.kind() == EntityKind.VALUE && rawNode.payload().isPresent()
                            ? new EntityNode(rawNode.id(), rawNode.typeId(), EntityKind.VALUE,
                                    Optional.of(interner.intern(rawNode.payload().get())))
                            : rawNode;
                    nextEntities = nextEntities.put(node.id().value(), node);
                    if (BootstrapIds.META_PROPAGATION_RULE_TYPE.equals(node.typeId())) {
                        if (nextPropRules == null) {
                            nextPropRules = new ArrayList<>(this.propagationRuleIds);
                        }
                        if (!nextPropRules.contains(node.id())) {
                            nextPropRules.add(node.id());
                            nextPropRules.sort(Comparator.naturalOrder());
                        }
                    }
                    if (node.kind() == EntityKind.META_TYPE
                            || node.kind() == EntityKind.SCALAR_TYPE
                            || BootstrapIds.META_PRINCIPAL_TYPE.equals(node.typeId())) {
                        touchedNameSubjects.add(node.id());
                    }
                }
                case Change.EntityRemoved(EntityNode removedNode) -> {
                    nextEntities = nextEntities.remove(removedNode.id().value());
                    if (BootstrapIds.META_PROPAGATION_RULE_TYPE.equals(removedNode.typeId())) {
                        if (nextPropRules == null) {
                            nextPropRules = new ArrayList<>(this.propagationRuleIds);
                        }
                        nextPropRules.remove(removedNode.id());
                    }
                    if (removedNode.kind() == EntityKind.META_TYPE
                            || removedNode.kind() == EntityKind.SCALAR_TYPE
                            || BootstrapIds.META_PRINCIPAL_TYPE.equals(removedNode.typeId())) {
                        touchedNameSubjects.add(removedNode.id());
                    }
                }
                case Change.ValueUpdated(EntityId entityId, _, Value newValue) -> {
                    EntityNode existing = nextEntities.get(entityId.value());
                    if (existing == null) {
                        throw new IllegalStateException("Missing entity during persistent delta update: " + entityId);
                    }
                    Value interned = interner.intern(newValue);
                    nextEntities = nextEntities.put(entityId.value(),
                            new EntityNode(entityId, existing.typeId(), EntityKind.VALUE, Optional.of(interned)));

                    List<Edge> incoming = nextIncoming.get(entityId.value());
                    if (incoming != null) {
                        for (Edge inEdge : incoming) {
                            if (BootstrapIds.HAS_NAME.equals(inEdge.predicateId())) {
                                touchedNameSubjects.add(inEdge.sourceId());
                            }
                        }
                    }
                }
                case Change.EdgeConnected(Edge edge) -> {
                    nextEntities = nextEntities.put(edge.id().value(),
                            new EntityNode(edge.id(), BootstrapIds.META_EDGE_TYPE, EntityKind.EDGE, Optional.empty()));
                    nextEdges = nextEdges.put(edge.id().value(), edge);
                    nextOutgoing = addEdgeToAdjacency(nextOutgoing, edge.sourceId().value(), edge);
                    nextIncoming = addEdgeToAdjacency(nextIncoming, edge.targetId().value(), edge);
                    if (BootstrapIds.HAS_NAME.equals(edge.predicateId())) {
                        touchedNameSubjects.add(edge.sourceId());
                    }
                }
                case Change.EdgeDisconnected(Edge edge) -> {
                    nextEdges = nextEdges.remove(edge.id().value());
                    nextEntities = nextEntities.remove(edge.id().value());
                    nextOutgoing = removeEdgeFromAdjacency(nextOutgoing, edge.sourceId().value(), edge.id());
                    nextIncoming = removeEdgeFromAdjacency(nextIncoming, edge.targetId().value(), edge.id());
                    if (BootstrapIds.HAS_NAME.equals(edge.predicateId())) {
                        touchedNameSubjects.add(edge.sourceId());
                    }
                }
            }
        }

        if (!touchedNameSubjects.isEmpty()) {
            nextTypes = new HashMap<>(this.typeByName);
            nextPredicates = new HashMap<>(this.predicateByName);
            nextPrincipals = new HashMap<>(this.principalByName);

            for (EntityId subjectId : touchedNameSubjects) {
                EntityId idToRemove = subjectId;
                nextTypes.values().removeIf(v -> v.equals(idToRemove));
                nextPredicates.values().removeIf(v -> v.equals(idToRemove));
                nextPrincipals.values().removeIf(v -> v.equals(idToRemove));

                EntityNode node = nextEntities.get(subjectId.value());
                if (node == null) {
                    continue;
                }
                String resolvedName = resolveNameFromMaps(nextEntities, nextOutgoing, subjectId);
                if (resolvedName == null) {
                    continue;
                }
                if (node.kind() == EntityKind.META_TYPE || node.kind() == EntityKind.SCALAR_TYPE) {
                    if (BootstrapIds.META_PREDICATE_TYPE.equals(node.typeId())) {
                        nextPredicates.put(resolvedName, subjectId);
                    } else {
                        nextTypes.put(resolvedName, subjectId);
                    }
                } else if (node.kind() == EntityKind.OBJECT && BootstrapIds.META_PRINCIPAL_TYPE.equals(node.typeId())) {
                    nextPrincipals.put(resolvedName, subjectId);
                }
            }
        }

        return new PersistentGraphSnapshot(
                nextEntities,
                nextEdges,
                nextOutgoing,
                nextIncoming,
                nextTypes != null ? Map.copyOf(nextTypes) : this.typeByName,
                nextPredicates != null ? Map.copyOf(nextPredicates) : this.predicateByName,
                nextPrincipals != null ? Map.copyOf(nextPrincipals) : this.principalByName,
                nextPropRules != null ? List.copyOf(nextPropRules) : this.propagationRuleIds
        );
    }

    MutableGraphState toMutableState(AtomicLong sharedAllocator) {
        MutableGraphState state = new MutableGraphState(sharedAllocator);
        entities.forEach(node -> state.entities.put(node.id(), node));
        edges.forEach(edge -> state.edges.put(edge.id(), edge));
        state.rebuildDerivedIndexes();
        return state;
    }

    void collectUniqueTrieNodes(Set<Object> seenNodes) {
        entities.collectUniqueTrieNodes(seenNodes);
        edges.collectUniqueTrieNodes(seenNodes);
        outgoingEdges.collectUniqueTrieNodes(seenNodes);
        incomingEdges.collectUniqueTrieNodes(seenNodes);
    }

    @Override
    public Optional<EntityNode> findEntity(EntityId id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(entities.get(id.value()));
    }

    @Override
    public Optional<Edge> findEdge(EntityId edgeId) {
        Objects.requireNonNull(edgeId, "edgeId must not be null");
        return Optional.ofNullable(edges.get(edgeId.value()));
    }

    @Override
    public Optional<EntityId> findTypeByName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(typeByName.get(name));
    }

    @Override
    public Optional<EntityId> findPredicateByName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(predicateByName.get(name));
    }

    @Override
    public Optional<EntityId> findPrincipalByName(String principalId) {
        return principalId == null ? Optional.empty() : Optional.ofNullable(principalByName.get(principalId));
    }

    @Override
    public Optional<String> resolveName(EntityId schemaEntityId) {
        Objects.requireNonNull(schemaEntityId, "schemaEntityId must not be null");
        return Optional.ofNullable(resolveNameFromMaps(entities, outgoingEdges, schemaEntityId));
    }

    @Override
    public Optional<ValueMutability> resolveValueMutability(EntityId valueEntityId) {
        Objects.requireNonNull(valueEntityId, "valueEntityId must not be null");
        EntityNode node = entities.get(valueEntityId.value());
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
        EntityNode node = entities.get(predicateId.value());
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
        EntityNode node = entities.get(valueTypeId.value());
        if (node == null || !BootstrapIds.META_VALUE_TYPE.equals(node.typeId())) {
            return false;
        }
        return resolveBooleanFlag(valueTypeId, BootstrapIds.HAS_UNIQUE_VALUES);
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        List<Edge> list = outgoingEdges.get(sourceId.value());
        return list == null ? List.of() : list;
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return filterByPredicate(outgoingEdges.get(sourceId.value()), predicateId);
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        List<Edge> list = incomingEdges.get(targetId.value());
        return list == null ? List.of() : list;
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId, EntityId predicateId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return filterByPredicate(incomingEdges.get(targetId.value()), predicateId);
    }

    @Override
    public long entityCount() {
        return entities.size();
    }

    @Override
    public long edgeCount() {
        return edges.size();
    }

    @Override
    public List<EntityNode> allEntities() {
        return entities.valuesSorted(Comparator.comparing(EntityNode::id));
    }

    @Override
    public List<Edge> allEdges() {
        return edges.valuesSorted(Comparator.comparing(Edge::id));
    }

    @Override
    public AccessDecision evaluateAccess(String principalId, Permission permission, EntityId targetId) {
        return PermissionEvaluator.evaluate(this, principalId, permission, targetId);
    }

    @Override
    public List<EntityId> propagationRuleIds() {
        return propagationRuleIds;
    }

    @Override
    public List<DerivedAssertion> findDerivedOutgoingAssertions(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        return SemanticInferenceEngine.findDerivedOutgoingAssertions(this, sourceId, null);
    }

    @Override
    public List<DerivedAssertion> findDerivedOutgoingAssertions(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return SemanticInferenceEngine.findDerivedOutgoingAssertions(this, sourceId, predicateId);
    }

    @Override
    public List<DerivedAssertion> findAllOutgoingAssertions(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        return SemanticInferenceEngine.findAllOutgoingAssertions(this, sourceId, null);
    }

    @Override
    public List<DerivedAssertion> findAllOutgoingAssertions(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return SemanticInferenceEngine.findAllOutgoingAssertions(this, sourceId, predicateId);
    }

    private static PersistentLongMap<List<Edge>> addEdgeToAdjacency(
            PersistentLongMap<List<Edge>> map,
            long ownerKey,
            Edge edge
    ) {
        List<Edge> existing = map.get(ownerKey);
        if (existing == null || existing.isEmpty()) {
            return map.put(ownerKey, List.of(edge));
        }
        List<Edge> updated = new ArrayList<>(existing.size() + 1);
        boolean inserted = false;
        for (Edge current : existing) {
            if (!inserted && edge.id().compareTo(current.id()) < 0) {
                updated.add(edge);
                inserted = true;
            }
            if (current.id().equals(edge.id())) {
                updated.add(edge);
                inserted = true;
            } else {
                updated.add(current);
            }
        }
        if (!inserted) {
            updated.add(edge);
        }
        return map.put(ownerKey, List.copyOf(updated));
    }

    private static PersistentLongMap<List<Edge>> removeEdgeFromAdjacency(
            PersistentLongMap<List<Edge>> map,
            long ownerKey,
            EntityId edgeId
    ) {
        List<Edge> existing = map.get(ownerKey);
        if (existing == null || existing.isEmpty()) {
            return map;
        }
        List<Edge> updated = new ArrayList<>(existing.size());
        boolean changed = false;
        for (Edge e : existing) {
            if (e.id().equals(edgeId)) {
                changed = true;
            } else {
                updated.add(e);
            }
        }
        if (!changed) {
            return map;
        }
        return updated.isEmpty() ? map.remove(ownerKey) : map.put(ownerKey, List.copyOf(updated));
    }

    private static String resolveNameFromMaps(
            PersistentLongMap<EntityNode> entities,
            PersistentLongMap<List<Edge>> outgoingEdges,
            EntityId entityId
    ) {
        List<Edge> out = outgoingEdges.get(entityId.value());
        if (out == null || out.isEmpty()) {
            return null;
        }
        for (Edge edge : out) {
            if (BootstrapIds.HAS_NAME.equals(edge.predicateId())) {
                EntityNode nameNode = entities.get(edge.targetId().value());
                if (nameNode != null && nameNode.payload().isPresent()
                        && nameNode.payload().get() instanceof Value.StringValue(String val)) {
                    return val;
                }
            }
        }
        return null;
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

    private OptionalInt resolveOptionalIntFlag(EntityId sourceId, EntityId flagPredicateId) {
        List<Edge> flagEdges = findOutgoingEdges(sourceId, flagPredicateId);
        if (flagEdges.isEmpty()) {
            return OptionalInt.empty();
        }
        EntityNode flagNode = entities.get(flagEdges.getFirst().targetId().value());
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
        EntityNode flagNode = entities.get(flagEdges.getFirst().targetId().value());
        return flagNode != null && flagNode.payload().isPresent()
                && flagNode.payload().get() instanceof Value.BooleanValue(boolean val) && val;
    }
}
