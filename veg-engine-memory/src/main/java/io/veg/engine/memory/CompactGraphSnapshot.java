package io.veg.engine.memory;

import io.veg.engine.GraphReader;
import io.veg.model.AccessDecision;
import io.veg.model.BootstrapIds;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Engine B immutable graph snapshot backed by primitive {@code long[]}/{@code byte[]}/{@code Value[]}
 * node/edge tables and Compressed Sparse Row (CSR) outgoing/incoming adjacency arrays.
 */
public final class CompactGraphSnapshot implements GraphReader, PropagationRuleSource {

    private static final EntityKind[] ENTITY_KINDS = EntityKind.values();

    private final long[] entityIds;
    private final long[] entityTypeIds;
    private final byte[] entityKinds;
    private final Value[] entityPayloads;

    private final long[] edgeIds;
    private final long[] edgeSources;
    private final long[] edgePredicates;
    private final long[] edgeTargets;

    private final long[] outOwnerIds;
    private final int[] outOffsets;
    private final int[] outEdgeIndices;

    private final long[] inOwnerIds;
    private final int[] inOffsets;
    private final int[] inEdgeIndices;

    private final long[] indexedTypeIds;
    private final int[] typeOffsets;
    private final int[] typeEntityIndices;
    private final SnapshotAclCache aclCache = new SnapshotAclCache();

    private final String[] typeNames;
    private final long[] typeIds;
    private final String[] predicateNames;
    private final long[] predicateIds;
    private final String[] principalNames;
    private final long[] principalIds;
    private final List<EntityId> propagationRuleIds;

    private CompactGraphSnapshot(
            long[] entityIds,
            long[] entityTypeIds,
            byte[] entityKinds,
            Value[] entityPayloads,
            long[] edgeIds,
            long[] edgeSources,
            long[] edgePredicates,
            long[] edgeTargets,
            long[] outOwnerIds,
            int[] outOffsets,
            int[] outEdgeIndices,
            long[] inOwnerIds,
            int[] inOffsets,
            int[] inEdgeIndices,
            long[] indexedTypeIds,
            int[] typeOffsets,
            int[] typeEntityIndices,
            String[] typeNames,
            long[] typeIds,
            String[] predicateNames,
            long[] predicateIds,
            String[] principalNames,
            long[] principalIds,
            List<EntityId> propagationRuleIds
    ) {
        this.entityIds = entityIds;
        this.entityTypeIds = entityTypeIds;
        this.entityKinds = entityKinds;
        this.entityPayloads = entityPayloads;
        this.edgeIds = edgeIds;
        this.edgeSources = edgeSources;
        this.edgePredicates = edgePredicates;
        this.edgeTargets = edgeTargets;
        this.outOwnerIds = outOwnerIds;
        this.outOffsets = outOffsets;
        this.outEdgeIndices = outEdgeIndices;
        this.inOwnerIds = inOwnerIds;
        this.inOffsets = inOffsets;
        this.inEdgeIndices = inEdgeIndices;
        this.indexedTypeIds = indexedTypeIds;
        this.typeOffsets = typeOffsets;
        this.typeEntityIndices = typeEntityIndices;
        this.typeNames = typeNames;
        this.typeIds = typeIds;
        this.predicateNames = predicateNames;
        this.predicateIds = predicateIds;
        this.principalNames = principalNames;
        this.principalIds = principalIds;
        this.propagationRuleIds = propagationRuleIds;
    }

    static CompactGraphSnapshot fromState(MutableGraphState state, ValueInterner interner) {
        List<EntityNode> sortedEntities = state.entities.values().stream()
                .sorted(Comparator.comparing(EntityNode::id))
                .toList();
        int nEnt = sortedEntities.size();
        long[] entityIds = new long[nEnt];
        long[] entityTypeIds = new long[nEnt];
        byte[] entityKinds = new byte[nEnt];
        Value[] entityPayloads = new Value[nEnt];
        List<EntityId> propRules = new ArrayList<>();

        for (int i = 0; i < nEnt; i++) {
            EntityNode node = sortedEntities.get(i);
            entityIds[i] = node.id().value();
            entityTypeIds[i] = node.typeId().value();
            entityKinds[i] = (byte) node.kind().ordinal();
            entityPayloads[i] = node.payload().map(interner::intern).orElse(null);
            if (BootstrapIds.META_PROPAGATION_RULE_TYPE.equals(node.typeId())) {
                propRules.add(node.id());
            }
        }

        List<Edge> sortedEdges = state.edges.values().stream()
                .sorted(Comparator.comparing(Edge::id))
                .toList();
        int nEdg = sortedEdges.size();
        long[] edgeIds = new long[nEdg];
        long[] edgeSources = new long[nEdg];
        long[] edgePredicates = new long[nEdg];
        long[] edgeTargets = new long[nEdg];
        for (int i = 0; i < nEdg; i++) {
            Edge e = sortedEdges.get(i);
            edgeIds[i] = e.id().value();
            edgeSources[i] = e.sourceId().value();
            edgePredicates[i] = e.predicateId().value();
            edgeTargets[i] = e.targetId().value();
        }

        CsrAdjacency outgoingCsr = buildCsr(edgeSources, edgeIds);
        CsrAdjacency incomingCsr = buildCsr(edgeTargets, edgeIds);
        CsrAdjacency typeIndexCsr = buildCsr(entityTypeIds, entityIds);

        NameTable types = buildNameTable(state.typeByName);
        NameTable preds = buildNameTable(state.predicateByName);
        NameTable principals = buildNameTable(state.principalByName);

        return new CompactGraphSnapshot(
                entityIds, entityTypeIds, entityKinds, entityPayloads,
                edgeIds, edgeSources, edgePredicates, edgeTargets,
                outgoingCsr.owners(), outgoingCsr.offsets(), outgoingCsr.edgeIndices(),
                incomingCsr.owners(), incomingCsr.offsets(), incomingCsr.edgeIndices(),
                typeIndexCsr.owners(), typeIndexCsr.offsets(), typeIndexCsr.edgeIndices(),
                types.names(), types.ids(),
                preds.names(), preds.ids(),
                principals.names(), principals.ids(),
                List.copyOf(propRules)
        );
    }

    MutableGraphState toMutableState(AtomicLong sharedAllocator) {
        MutableGraphState state = new MutableGraphState(sharedAllocator);
        for (int i = 0; i < entityIds.length; i++) {
            EntityNode node = nodeAt(i);
            state.entities.put(node.id(), node);
        }
        for (int i = 0; i < edgeIds.length; i++) {
            Edge edge = edgeAt(i);
            state.edges.put(edge.id(), edge);
        }
        state.rebuildDerivedIndexes();
        return state;
    }

    public long estimatedFootprintBytes() {
        long bytes = 0L;
        bytes += 32L + (long) entityIds.length * (8L + 8L + 1L + 8L);
        bytes += 32L + (long) edgeIds.length * (8L * 4L);
        bytes += 24L + (long) outOwnerIds.length * 8L + (long) outOffsets.length * 4L + (long) outEdgeIndices.length * 4L;
        bytes += 24L + (long) inOwnerIds.length * 8L + (long) inOffsets.length * 4L + (long) inEdgeIndices.length * 4L;
        bytes += (long) (typeNames.length + predicateNames.length + principalNames.length) * 16L;
        bytes += (long) propagationRuleIds.size() * 16L;
        return bytes;
    }

    @Override
    public Optional<EntityNode> findEntity(EntityId id) {
        Objects.requireNonNull(id, "id must not be null");
        int idx = Arrays.binarySearch(entityIds, id.value());
        return idx >= 0 ? Optional.of(nodeAt(idx)) : Optional.empty();
    }

    @Override
    public Optional<Edge> findEdge(EntityId edgeId) {
        Objects.requireNonNull(edgeId, "edgeId must not be null");
        int idx = Arrays.binarySearch(edgeIds, edgeId.value());
        return idx >= 0 ? Optional.of(edgeAt(idx)) : Optional.empty();
    }

    @Override
    public Optional<EntityId> findTypeByName(String name) {
        return lookupNameTable(typeNames, typeIds, name);
    }

    @Override
    public Optional<EntityId> findPredicateByName(String name) {
        return lookupNameTable(predicateNames, predicateIds, name);
    }

    @Override
    public Optional<EntityId> findPrincipalByName(String principalId) {
        return lookupNameTable(principalNames, principalIds, principalId);
    }

    @Override
    public Optional<String> resolveName(EntityId schemaEntityId) {
        Objects.requireNonNull(schemaEntityId, "schemaEntityId must not be null");
        List<Edge> nameEdges = findOutgoingEdges(schemaEntityId, BootstrapIds.HAS_NAME);
        if (nameEdges.isEmpty()) {
            return Optional.empty();
        }
        Optional<EntityNode> nameNode = findEntity(nameEdges.getFirst().targetId());
        if (nameNode.isPresent() && nameNode.get().payload().isPresent()
                && nameNode.get().payload().get() instanceof Value.StringValue(String val)) {
            return Optional.of(val);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ValueMutability> resolveValueMutability(EntityId valueEntityId) {
        Objects.requireNonNull(valueEntityId, "valueEntityId must not be null");
        Optional<EntityNode> node = findEntity(valueEntityId);
        if (node.isEmpty() || node.get().kind() != EntityKind.VALUE) {
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
        Optional<EntityNode> node = findEntity(predicateId);
        if (node.isEmpty() || !BootstrapIds.META_PREDICATE_TYPE.equals(node.get().typeId())) {
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
        Optional<EntityNode> node = findEntity(valueTypeId);
        if (node.isEmpty() || !BootstrapIds.META_VALUE_TYPE.equals(node.get().typeId())) {
            return false;
        }
        return resolveBooleanFlag(valueTypeId, BootstrapIds.HAS_UNIQUE_VALUES);
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        return readCsrEdges(outOwnerIds, outOffsets, outEdgeIndices, sourceId.value(), null);
    }

    @Override
    public List<Edge> findOutgoingEdges(EntityId sourceId, EntityId predicateId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return readCsrEdges(outOwnerIds, outOffsets, outEdgeIndices, sourceId.value(), predicateId);
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        return readCsrEdges(inOwnerIds, inOffsets, inEdgeIndices, targetId.value(), null);
    }

    @Override
    public List<Edge> findIncomingEdges(EntityId targetId, EntityId predicateId) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        return readCsrEdges(inOwnerIds, inOffsets, inEdgeIndices, targetId.value(), predicateId);
    }

    @Override
    public long entityCount() {
        return entityIds.length;
    }

    @Override
    public long edgeCount() {
        return edgeIds.length;
    }

    @Override
    public List<EntityNode> findEntitiesByType(EntityId typeId) {
        Objects.requireNonNull(typeId, "typeId must not be null");
        int slot = Arrays.binarySearch(indexedTypeIds, typeId.value());
        if (slot < 0) {
            return List.of();
        }
        int start = typeOffsets[slot];
        int end = typeOffsets[slot + 1];
        List<EntityNode> result = new ArrayList<>(end - start);
        for (int pos = start; pos < end; pos++) {
            result.add(nodeAt(typeEntityIndices[pos]));
        }
        return List.copyOf(result);
    }

    @Override
    public List<EntityNode> allEntities() {
        List<EntityNode> list = new ArrayList<>(entityIds.length);
        for (int i = 0; i < entityIds.length; i++) {
            list.add(nodeAt(i));
        }
        return List.copyOf(list);
    }

    @Override
    public List<Edge> allEdges() {
        List<Edge> list = new ArrayList<>(edgeIds.length);
        for (int i = 0; i < edgeIds.length; i++) {
            list.add(edgeAt(i));
        }
        return List.copyOf(list);
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

    private EntityNode nodeAt(int idx) {
        return new EntityNode(
                new EntityId(entityIds[idx]),
                new EntityId(entityTypeIds[idx]),
                ENTITY_KINDS[entityKinds[idx]],
                Optional.ofNullable(entityPayloads[idx])
        );
    }

    private Edge edgeAt(int idx) {
        return new Edge(
                new EntityId(edgeIds[idx]),
                new EntityId(edgeSources[idx]),
                new EntityId(edgePredicates[idx]),
                new EntityId(edgeTargets[idx])
        );
    }

    private List<Edge> readCsrEdges(long[] ownerIds, int[] offsets, int[] edgeIndices, long ownerId, EntityId predicateFilter) {
        int slot = Arrays.binarySearch(ownerIds, ownerId);
        if (slot < 0) {
            return List.of();
        }
        int start = offsets[slot];
        int end = offsets[slot + 1];
        List<Edge> result = new ArrayList<>(end - start);
        if (predicateFilter == null) {
            for (int pos = start; pos < end; pos++) {
                result.add(edgeAt(edgeIndices[pos]));
            }
        } else {
            long filterVal = predicateFilter.value();
            for (int pos = start; pos < end; pos++) {
                int eIdx = edgeIndices[pos];
                if (edgePredicates[eIdx] == filterVal) {
                    result.add(edgeAt(eIdx));
                }
            }
        }
        return List.copyOf(result);
    }

    private OptionalInt resolveOptionalIntFlag(EntityId sourceId, EntityId flagPredicateId) {
        List<Edge> flagEdges = findOutgoingEdges(sourceId, flagPredicateId);
        if (flagEdges.isEmpty()) {
            return OptionalInt.empty();
        }
        Optional<EntityNode> flagNode = findEntity(flagEdges.getFirst().targetId());
        if (flagNode.isPresent() && flagNode.get().payload().isPresent()
                && flagNode.get().payload().get() instanceof Value.IntValue(long val)) {
            return OptionalInt.of(Math.toIntExact(val));
        }
        return OptionalInt.empty();
    }

    private boolean resolveBooleanFlag(EntityId sourceId, EntityId flagPredicateId) {
        List<Edge> flagEdges = findOutgoingEdges(sourceId, flagPredicateId);
        if (flagEdges.isEmpty()) {
            return false;
        }
        Optional<EntityNode> flagNode = findEntity(flagEdges.getFirst().targetId());
        return flagNode.isPresent() && flagNode.get().payload().isPresent()
                && flagNode.get().payload().get() instanceof Value.BooleanValue(boolean val) && val;
    }

    private static Optional<EntityId> lookupNameTable(String[] names, long[] ids, String query) {
        if (query == null) {
            return Optional.empty();
        }
        int idx = Arrays.binarySearch(names, query);
        return idx >= 0 ? Optional.of(new EntityId(ids[idx])) : Optional.empty();
    }

    private record NameTable(String[] names, long[] ids) {
    }

    private static NameTable buildNameTable(Map<String, EntityId> map) {
        List<Map.Entry<String, EntityId>> entries = map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        String[] names = new String[entries.size()];
        long[] ids = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            names[i] = entries.get(i).getKey();
            ids[i] = entries.get(i).getValue().value();
        }
        return new NameTable(names, ids);
    }

    private record CsrAdjacency(long[] owners, int[] offsets, int[] edgeIndices) {
    }

    private static CsrAdjacency buildCsr(long[] edgeEndpointIds, long[] sortedEdgeIds) {
        int n = edgeEndpointIds.length;
        if (n == 0) {
            return new CsrAdjacency(new long[0], new int[]{0}, new int[0]);
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int cmp = Long.compare(edgeEndpointIds[a], edgeEndpointIds[b]);
            return cmp != 0 ? cmp : Long.compare(sortedEdgeIds[a], sortedEdgeIds[b]);
        });

        int distinctOwners = 1;
        for (int i = 1; i < n; i++) {
            if (edgeEndpointIds[order[i]] != edgeEndpointIds[order[i - 1]]) {
                distinctOwners++;
            }
        }

        long[] owners = new long[distinctOwners];
        int[] offsets = new int[distinctOwners + 1];
        int[] edgeIndices = new int[n];

        int ownerIdx = 0;
        owners[0] = edgeEndpointIds[order[0]];
        offsets[0] = 0;
        edgeIndices[0] = order[0];
        for (int i = 1; i < n; i++) {
            int idx = order[i];
            edgeIndices[i] = idx;
            long owner = edgeEndpointIds[idx];
            if (owner != owners[ownerIdx]) {
                ownerIdx++;
                owners[ownerIdx] = owner;
                offsets[ownerIdx] = i;
            }
        }
        offsets[distinctOwners] = n;
        return new CsrAdjacency(owners, offsets, edgeIndices);
    }
}
