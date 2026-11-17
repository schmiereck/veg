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
import java.util.Collection;
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
import java.util.function.Function;

/**
 * Engine C immutable graph snapshot backed by structurally shared {@link PersistentLongMap} radix tries.
 *
 * <p>Each commit derives a new snapshot solely from its delta {@code List<Change>}, sharing all
 * unmodified trie nodes physically with the parent snapshot ({@code P-04}, {@code H3}).</p>
 */
public final class PersistentGraphSnapshot implements GraphReader, PropagationRuleSource {

    private static final PersistentGraphSnapshot EMPTY = new PersistentGraphSnapshot(
            PersistentEntityMap.empty(),
            PersistentEntityMap.empty(),
            PersistentEntityMap.empty(),
            PersistentEntityMap.empty(),
            PersistentEntityMap.empty(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of()
    );

    private final PersistentEntityMap<EntityNode> entities;
    private final PersistentEntityMap<Edge> edges;
    private final PersistentEntityMap<List<Edge>> outgoingEdges;
    private final PersistentEntityMap<List<Edge>> incomingEdges;
    private final PersistentEntityMap<List<EntityNode>> entitiesByType;
    private final Map<String, EntityId> typeByName;
    private final Map<String, EntityId> predicateByName;
    private final Map<String, EntityId> principalByName;
    private final List<EntityId> propagationRuleIds;
    private final SnapshotAclCache aclCache = new SnapshotAclCache();

    private PersistentGraphSnapshot(
            PersistentEntityMap<EntityNode> entities,
            PersistentEntityMap<Edge> edges,
            PersistentEntityMap<List<Edge>> outgoingEdges,
            PersistentEntityMap<List<Edge>> incomingEdges,
            PersistentEntityMap<List<EntityNode>> entitiesByType,
            Map<String, EntityId> typeByName,
            Map<String, EntityId> predicateByName,
            Map<String, EntityId> principalByName,
            List<EntityId> propagationRuleIds
    ) {
        this.entities = entities;
        this.edges = edges;
        this.outgoingEdges = outgoingEdges;
        this.incomingEdges = incomingEdges;
        this.entitiesByType = entitiesByType;
        this.typeByName = typeByName;
        this.predicateByName = predicateByName;
        this.principalByName = principalByName;
        this.propagationRuleIds = propagationRuleIds;
    }

    static PersistentGraphSnapshot empty() {
        return EMPTY;
    }

    /**
     * Returns an empty persistent snapshot instance for snapshot replay or checkpoint loading.
     */
    public static PersistentGraphSnapshot emptySnapshot() {
        return EMPTY;
    }

    /**
     * Reconstructs an immutable {@link PersistentGraphSnapshot} from materialized entity nodes and edges
     * (e.g., when loading a persisted checkpoint from disk).
     */
    public static PersistentGraphSnapshot fromMaterialized(
            Collection<EntityNode> nodes,
            Collection<Edge> edges
    ) {
        Objects.requireNonNull(nodes, "nodes must not be null");
        Objects.requireNonNull(edges, "edges must not be null");
        PersistentEntityMap<EntityNode> nextEntities = PersistentEntityMap.empty();
        PersistentEntityMap<Edge> nextEdges = PersistentEntityMap.empty();
        PersistentEntityMap<List<Edge>> nextOutgoing = PersistentEntityMap.empty();
        PersistentEntityMap<List<Edge>> nextIncoming = PersistentEntityMap.empty();
        List<EntityId> propRules = new ArrayList<>();

        for (EntityNode node : nodes) {
            nextEntities = nextEntities.put(node.id(), node);
            if (BootstrapIds.META_PROPAGATION_RULE_TYPE.equals(node.typeId()) && !propRules.contains(node.id())) {
                propRules.add(node.id());
            }
        }
        propRules.sort(Comparator.naturalOrder());

        for (Edge edge : edges) {
            nextEntities = nextEntities.put(edge.id(),
                    new EntityNode(edge.id(), BootstrapIds.META_EDGE_TYPE, EntityKind.EDGE, Optional.empty()));
            nextEdges = nextEdges.put(edge.id(), edge);
            nextOutgoing = addToBucket(nextOutgoing, edge.sourceId(), edge, Edge::id);
            nextIncoming = addToBucket(nextIncoming, edge.targetId(), edge, Edge::id);
        }

        Map<EntityId, List<EntityNode>> groupedByType = new HashMap<>();
        nextEntities.forEach(node ->
                groupedByType.computeIfAbsent(node.typeId(), ignored -> new ArrayList<>()).add(node));
        PersistentEntityMap<List<EntityNode>> nextEntitiesByType = PersistentEntityMap.empty();
        for (var entry : groupedByType.entrySet()) {
            List<EntityNode> list = entry.getValue();
            list.sort(Comparator.comparing(EntityNode::id));
            nextEntitiesByType = nextEntitiesByType.put(entry.getKey(), List.copyOf(list));
        }

        Map<String, EntityId> types = new HashMap<>();
        Map<String, EntityId> predicates = new HashMap<>();
        Map<String, EntityId> principals = new HashMap<>();
        for (EntityNode node : nodes) {
            indexSubjectNameIfApplicable(node, nextEntities, nextOutgoing, types, predicates, principals);
        }

        return new PersistentGraphSnapshot(
                nextEntities,
                nextEdges,
                nextOutgoing,
                nextIncoming,
                nextEntitiesByType,
                Map.copyOf(types),
                Map.copyOf(predicates),
                Map.copyOf(principals),
                List.copyOf(propRules)
        );
    }

    /**
     * Applies a list of committed {@link Change} deltas onto this immutable snapshot.
     */
    public PersistentGraphSnapshot applyDelta(List<Change> changes) {
        return applyChanges(changes, new ValueInterner());
    }

    PersistentGraphSnapshot applyChanges(List<Change> changes, ValueInterner interner) {
        if (changes.isEmpty()) {
            return this;
        }

        PersistentEntityMap<EntityNode> nextEntities = this.entities;
        PersistentEntityMap<Edge> nextEdges = this.edges;
        PersistentEntityMap<List<Edge>> nextOutgoing = this.outgoingEdges;
        PersistentEntityMap<List<Edge>> nextIncoming = this.incomingEdges;
        PersistentEntityMap<List<EntityNode>> nextEntitiesByType = this.entitiesByType;

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
                    EntityNode old = nextEntities.get(node.id());
                    if (old != null && !old.typeId().equals(node.typeId())) {
                        nextEntitiesByType = removeFromBucket(nextEntitiesByType, old.typeId(), old.id(), EntityNode::id);
                    }
                    nextEntities = nextEntities.put(node.id(), node);
                    nextEntitiesByType = addToBucket(nextEntitiesByType, node.typeId(), node, EntityNode::id);
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
                    EntityNode existing = nextEntities.get(removedNode.id());
                    nextEntities = nextEntities.remove(removedNode.id());
                    if (existing != null) {
                        nextEntitiesByType = removeFromBucket(
                                nextEntitiesByType, existing.typeId(), existing.id(), EntityNode::id);
                    }
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
                    EntityNode existing = nextEntities.get(entityId);
                    if (existing == null) {
                        throw new IllegalStateException("Missing entity during persistent delta update: " + entityId);
                    }
                    Value interned = interner.intern(newValue);
                    EntityNode updated = new EntityNode(entityId, existing.typeId(), EntityKind.VALUE, Optional.of(interned));
                    nextEntities = nextEntities.put(entityId, updated);
                    nextEntitiesByType = addToBucket(nextEntitiesByType, updated.typeId(), updated, EntityNode::id);

                    List<Edge> incoming = nextIncoming.get(entityId);
                    if (incoming != null) {
                        for (Edge inEdge : incoming) {
                            if (BootstrapIds.HAS_NAME.equals(inEdge.predicateId())) {
                                touchedNameSubjects.add(inEdge.sourceId());
                            }
                        }
                    }
                }
                case Change.EdgeConnected(Edge edge) -> {
                    EntityNode edgeNode = new EntityNode(edge.id(), BootstrapIds.META_EDGE_TYPE, EntityKind.EDGE, Optional.empty());
                    nextEntities = nextEntities.put(edge.id(), edgeNode);
                    nextEntitiesByType = addToBucket(nextEntitiesByType, BootstrapIds.META_EDGE_TYPE, edgeNode, EntityNode::id);
                    nextEdges = nextEdges.put(edge.id(), edge);
                    nextOutgoing = addToBucket(nextOutgoing, edge.sourceId(), edge, Edge::id);
                    nextIncoming = addToBucket(nextIncoming, edge.targetId(), edge, Edge::id);
                    if (BootstrapIds.HAS_NAME.equals(edge.predicateId())) {
                        touchedNameSubjects.add(edge.sourceId());
                    }
                }
                case Change.EdgeDisconnected(Edge edge) -> {
                    nextEdges = nextEdges.remove(edge.id());
                    nextEntities = nextEntities.remove(edge.id());
                    nextEntitiesByType = removeFromBucket(
                            nextEntitiesByType, BootstrapIds.META_EDGE_TYPE, edge.id(), EntityNode::id);
                    nextOutgoing = removeFromBucket(nextOutgoing, edge.sourceId(), edge.id(), Edge::id);
                    nextIncoming = removeFromBucket(nextIncoming, edge.targetId(), edge.id(), Edge::id);
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

                EntityNode node = nextEntities.get(subjectId);
                if (node != null) {
                    indexSubjectNameIfApplicable(
                            node, nextEntities, nextOutgoing, nextTypes, nextPredicates, nextPrincipals);
                }
            }
        }

        return new PersistentGraphSnapshot(
                nextEntities,
                nextEdges,
                nextOutgoing,
                nextIncoming,
                nextEntitiesByType,
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
        entitiesByType.collectUniqueTrieNodes(seenNodes);
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
        return list == null ? List.of() : list;
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return filterByPredicate(outgoingEdges.get(sourceId), predicateId);
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        List<Edge> list = incomingEdges.get(targetId);
        return list == null ? List.of() : list;
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId, EntityId predicateId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return filterByPredicate(incomingEdges.get(targetId), predicateId);
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
    public List<EntityNode> findEntitiesByType(EntityId typeId) {
        Objects.requireNonNull(typeId, "typeId must not be null");
        List<EntityNode> list = entitiesByType.get(typeId);
        return list == null ? List.of() : list;
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
        return aclCache.evaluate(this, principalId, permission, targetId);
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

    private static <T> PersistentEntityMap<List<T>> addToBucket(
            PersistentEntityMap<List<T>> map,
            EntityId ownerKey,
            T item,
            Function<T, EntityId> idExtractor
    ) {
        List<T> existing = map.get(ownerKey);
        if (existing == null || existing.isEmpty()) {
            return map.put(ownerKey, List.of(item));
        }
        EntityId itemId = idExtractor.apply(item);
        List<T> updated = new ArrayList<>(existing.size() + 1);
        boolean inserted = false;
        for (T current : existing) {
            EntityId curId = idExtractor.apply(current);
            if (!inserted && itemId.compareTo(curId) < 0) {
                updated.add(item);
                inserted = true;
            }
            if (curId.equals(itemId)) {
                updated.add(item);
                inserted = true;
            } else {
                updated.add(current);
            }
        }
        if (!inserted) {
            updated.add(item);
        }
        return map.put(ownerKey, List.copyOf(updated));
    }

    private static <T> PersistentEntityMap<List<T>> removeFromBucket(
            PersistentEntityMap<List<T>> map,
            EntityId ownerKey,
            EntityId itemId,
            Function<T, EntityId> idExtractor
    ) {
        List<T> existing = map.get(ownerKey);
        if (existing == null || existing.isEmpty()) {
            return map;
        }
        List<T> updated = new ArrayList<>(existing.size());
        boolean changed = false;
        for (T current : existing) {
            if (idExtractor.apply(current).equals(itemId)) {
                changed = true;
            } else {
                updated.add(current);
            }
        }
        if (!changed) {
            return map;
        }
        return updated.isEmpty() ? map.remove(ownerKey) : map.put(ownerKey, List.copyOf(updated));
    }

    private static void indexSubjectNameIfApplicable(
            EntityNode node,
            PersistentEntityMap<EntityNode> entities,
            PersistentEntityMap<List<Edge>> outgoingEdges,
            Map<String, EntityId> types,
            Map<String, EntityId> predicates,
            Map<String, EntityId> principals
    ) {
        boolean schemaOrPrincipal = node.kind() == EntityKind.META_TYPE
                || node.kind() == EntityKind.SCALAR_TYPE
                || (node.kind() == EntityKind.OBJECT && BootstrapIds.META_PRINCIPAL_TYPE.equals(node.typeId()));
        if (!schemaOrPrincipal) {
            return;
        }
        String resolvedName = resolveNameFromMaps(entities, outgoingEdges, node.id());
        if (resolvedName == null) {
            return;
        }
        if (node.kind() == EntityKind.META_TYPE || node.kind() == EntityKind.SCALAR_TYPE) {
            if (BootstrapIds.META_PREDICATE_TYPE.equals(node.typeId())) {
                predicates.put(resolvedName, node.id());
            } else {
                types.put(resolvedName, node.id());
            }
        } else {
            principals.put(resolvedName, node.id());
        }
    }

    private static String resolveNameFromMaps(
            PersistentEntityMap<EntityNode> entities,
            PersistentEntityMap<List<Edge>> outgoingEdges,
            EntityId entityId
    ) {
        List<Edge> out = outgoingEdges.get(entityId);
        if (out == null || out.isEmpty()) {
            return null;
        }
        for (Edge edge : out) {
            if (BootstrapIds.HAS_NAME.equals(edge.predicateId())) {
                EntityNode nameNode = entities.get(edge.targetId());
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
}
