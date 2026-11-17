package io.veg.engine.memory;

import io.veg.engine.SchemaViolationException;
import io.veg.model.BootstrapIds;
import io.veg.model.Change;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.GraphDiff;
import io.veg.model.MergeConflict;
import io.veg.model.MergeConflictType;
import io.veg.model.MergePolicy;
import io.veg.model.PredicateConstraints;
import io.veg.model.Value;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;

/**
 * Structural three-way merge engine over the commit DAG (V-07, V-12, V-13, H5, T10–T12).
 */
final class ThreeWayMergeEngine {

    record PreparedMerge(
            boolean succeeded,
            CommitId baseCommitId,
            MutableGraphState mergedState,
            List<Change> changesFromTarget,
            List<MergeConflict> conflicts
    ) {
    }

    private ThreeWayMergeEngine() {
    }

    static CommitId findCommonAncestor(List<Commit> commits, CommitId a, CommitId b) {
        Map<CommitId, Integer> distA = computeAncestorDistances(commits, a);
        Map<CommitId, Integer> distB = computeAncestorDistances(commits, b);

        CommitId best = CommitId.BOOTSTRAP;
        int bestCombinedDist = Integer.MAX_VALUE;

        for (var entry : distA.entrySet()) {
            CommitId candidate = entry.getKey();
            Integer bDist = distB.get(candidate);
            if (bDist == null) {
                continue;
            }
            int combined = entry.getValue() + bDist;
            if (combined < bestCombinedDist || (combined == bestCombinedDist && candidate.compareTo(best) > 0)) {
                best = candidate;
                bestCombinedDist = combined;
            }
        }
        return best;
    }

    static PreparedMerge prepareMerge(
            List<Commit> commits,
            Map<CommitId, InMemoryGraphSnapshot> snapshots,
            CommitId sourceCommitId,
            CommitId targetCommitId,
            MergePolicy policy
    ) {
        return prepareMerge(
                commits,
                commitId -> snapshots.get(commitId).state(),
                sourceCommitId,
                targetCommitId,
                policy
        );
    }

    static PreparedMerge prepareMerge(
            List<Commit> commits,
            Function<CommitId, MutableGraphState> stateLookup,
            CommitId sourceCommitId,
            CommitId targetCommitId,
            MergePolicy policy
    ) {
        CommitId baseCommitId = findCommonAncestor(commits, sourceCommitId, targetCommitId);
        MutableGraphState baseState = stateLookup.apply(baseCommitId);
        MutableGraphState targetState = stateLookup.apply(targetCommitId);
        InMemoryGraphSnapshot baseSnap = new InMemoryGraphSnapshot(baseState);
        InMemoryGraphSnapshot targetSnap = new InMemoryGraphSnapshot(targetState);
        MutableGraphState sourceState = buildFilteredSourceState(
                commits, stateLookup, baseCommitId, sourceCommitId, policy);
        InMemoryGraphSnapshot effectiveSourceSnap = new InMemoryGraphSnapshot(sourceState);

        if (baseCommitId.equals(sourceCommitId)) {
            return new PreparedMerge(true, baseCommitId, targetState.copy(), List.of(), List.of());
        }

        GraphDiff sourceDiff = GraphDiffCalculator.compute(baseCommitId, baseSnap, sourceCommitId, effectiveSourceSnap);
        GraphDiff targetDiff = GraphDiffCalculator.compute(baseCommitId, baseSnap, targetCommitId, targetSnap);

        List<MergeConflict> conflicts = new ArrayList<>();
        Set<EntityId> convergedSchemaSkipEntities = new HashSet<>();
        Set<EntityId> convergedSchemaSkipEdges = new HashSet<>();

        detectSchemaRenamesAndCollisions(baseState, sourceState, targetState, conflicts);
        detectConstraintConflicts(
                baseState, sourceState, targetState, conflicts, convergedSchemaSkipEntities, convergedSchemaSkipEdges);
        detectSharedValueConflicts(baseState, sourceDiff, targetDiff, conflicts);
        detectEdgeDeletedAndAnnotatedConflicts(sourceDiff, targetDiff, conflicts);
        detectConcurrentFunctionalAttributes(sourceState, targetState, sourceDiff, targetDiff, conflicts);

        if (!conflicts.isEmpty()) {
            return new PreparedMerge(false, baseCommitId, null, List.of(), conflicts);
        }

        MutableGraphState merged = applySourceDeltaOntoTarget(
                targetState, sourceDiff, convergedSchemaSkipEntities, convergedSchemaSkipEdges);

        validateMergedGraph(merged, conflicts);
        if (!conflicts.isEmpty()) {
            return new PreparedMerge(false, baseCommitId, null, List.of(), conflicts);
        }

        List<Change> changesFromTarget = computeChangesFromTarget(targetCommitId, targetSnap, merged);
        return new PreparedMerge(true, baseCommitId, merged, changesFromTarget, List.of());
    }

    private static MutableGraphState buildFilteredSourceState(
            List<Commit> commits,
            Function<CommitId, MutableGraphState> stateLookup,
            CommitId baseId,
            CommitId sourceId,
            MergePolicy policy
    ) {
        Set<CommitId> baseAncestors = computeAncestorDistances(commits, baseId).keySet();
        Set<CommitId> sourceReachable = computeAncestorDistances(commits, sourceId).keySet();
        List<Commit> sourceCommits = sourceReachable.stream()
                .filter(id -> !baseAncestors.contains(id))
                .sorted()
                .map(id -> commits.get((int) id.value()))
                .toList();

        boolean allIncluded = sourceCommits.stream().allMatch(c -> policy.includes(c.dataCategory()));
        if (allIncluded) {
            return stateLookup.apply(sourceId);
        }

        MutableGraphState filtered = stateLookup.apply(baseId).copy();
        for (Commit commit : sourceCommits) {
            boolean includeAllChanges = policy.includes(commit.dataCategory());
            for (Change change : commit.changes()) {
                if (includeAllChanges || isSchemaChange(filtered, change)) {
                    applyChangeSafely(filtered, change);
                }
            }
        }
        filtered.rebuildDerivedIndexes();
        return filtered;
    }

    private static boolean isSchemaChange(MutableGraphState state, Change change) {
        return switch (change) {
            case Change.EntityCreated(EntityNode node) ->
                    node.kind() == EntityKind.META_TYPE || node.typeId().isBootstrap();
            case Change.EntityRemoved(EntityNode node) ->
                    node.kind() == EntityKind.META_TYPE || node.typeId().isBootstrap();
            case Change.ValueUpdated(EntityId entityId, _, _) -> {
                EntityNode node = state.entities.get(entityId);
                yield node != null && node.typeId().isBootstrap();
            }
            case Change.EdgeConnected(Edge edge) -> GraphSchemaValidator.isSchemaStructuralPredicate(edge.predicateId());
            case Change.EdgeDisconnected(Edge edge) -> GraphSchemaValidator.isSchemaStructuralPredicate(edge.predicateId());
        };
    }

    private static void applyChangeSafely(MutableGraphState state, Change change) {
        switch (change) {
            case Change.EntityCreated(EntityNode node) -> {
                if (node.typeId().isBootstrap() || state.entities.containsKey(node.typeId())) {
                    state.putEntity(node);
                }
            }
            case Change.EdgeConnected(Edge edge) -> {
                if (state.entities.containsKey(edge.sourceId())
                        && state.entities.containsKey(edge.predicateId())
                        && state.entities.containsKey(edge.targetId())) {
                    state.putEdge(edge);
                }
            }
            case Change.ValueUpdated(EntityId id, _, _) -> {
                if (state.entities.containsKey(id)) {
                    state.applyReplayChange(change);
                }
            }
            case Change.EdgeDisconnected(Edge edge) -> state.removeEdge(edge.id());
            case Change.EntityRemoved(EntityNode node) -> state.entities.remove(node.id());
        }
    }

    private static void detectSchemaRenamesAndCollisions(
            MutableGraphState base,
            MutableGraphState source,
            MutableGraphState target,
            List<MergeConflict> conflicts
    ) {
        for (EntityNode baseNode : base.entities.values()) {
            if (baseNode.id().isBootstrap() || baseNode.kind() != EntityKind.META_TYPE) {
                continue;
            }
            Optional<String> baseName = base.resolveName(baseNode.id());
            Optional<String> srcName = source.resolveName(baseNode.id());
            Optional<String> tgtName = target.resolveName(baseNode.id());
            if (!srcName.equals(baseName) && !tgtName.equals(baseName) && !srcName.equals(tgtName)) {
                conflicts.add(new MergeConflict(
                        MergeConflictType.CONCURRENT_SCHEMA_RENAME,
                        baseNode.id(),
                        "Schema element " + baseNode.id() + " renamed incompatibly: source="
                                + srcName.orElse("?") + ", target=" + tgtName.orElse("?")
                ));
            }
        }

        checkNameCollisions(source.typeByName, target.typeByName, conflicts);
        checkNameCollisions(source.predicateByName, target.predicateByName, conflicts);
    }

    private static void checkNameCollisions(
            Map<String, EntityId> sourceNames,
            Map<String, EntityId> targetNames,
            List<MergeConflict> conflicts
    ) {
        for (var entry : sourceNames.entrySet()) {
            EntityId targetOwner = targetNames.get(entry.getKey());
            if (targetOwner != null && !targetOwner.equals(entry.getValue())) {
                conflicts.add(new MergeConflict(
                        MergeConflictType.CONCURRENT_SCHEMA_RENAME,
                        entry.getValue(),
                        "Schema name collision for '" + entry.getKey()
                                + "': source=" + entry.getValue() + ", target=" + targetOwner
                ));
            }
        }
    }

    private static void detectConstraintConflicts(
            MutableGraphState base,
            MutableGraphState source,
            MutableGraphState target,
            List<MergeConflict> conflicts,
            Set<EntityId> convergedSkipEntities,
            Set<EntityId> convergedSkipEdges
    ) {
        for (EntityNode baseNode : base.entities.values()) {
            if (baseNode.id().isBootstrap() || !BootstrapIds.META_PREDICATE_TYPE.equals(baseNode.typeId())) {
                continue;
            }
            EntityId predId = baseNode.id();
            Optional<PredicateConstraints> baseC = base.resolvePredicateConstraints(predId);
            Optional<PredicateConstraints> srcC = source.resolvePredicateConstraints(predId);
            Optional<PredicateConstraints> tgtC = target.resolvePredicateConstraints(predId);
            if (!srcC.equals(baseC) && !tgtC.equals(baseC)) {
                if (!srcC.equals(tgtC)) {
                    conflicts.add(new MergeConflict(
                            MergeConflictType.CONCURRENT_CONSTRAINT_CHANGE,
                            predId,
                            "Incompatible constraint changes on predicate " + predId
                                    + ": source=" + srcC + ", target=" + tgtC
                    ));
                } else {
                    markAuxiliaryConstraintEdgesSkipped(base, source, predId, BootstrapIds.HAS_MAX_OUTGOING, convergedSkipEntities, convergedSkipEdges);
                    markAuxiliaryConstraintEdgesSkipped(base, source, predId, BootstrapIds.HAS_MAX_INCOMING, convergedSkipEntities, convergedSkipEdges);
                }
            }
        }
    }

    private static void markAuxiliaryConstraintEdgesSkipped(
            MutableGraphState base,
            MutableGraphState source,
            EntityId predId,
            EntityId metaPredId,
            Set<EntityId> skipEntities,
            Set<EntityId> skipEdges
    ) {
        for (Edge e : source.findOutgoingEdges(predId, metaPredId)) {
            if (base.edges.containsKey(e.id())) {
                continue;
            }
            skipEdges.add(e.id());
            skipEntities.add(e.targetId());
            for (Edge mutEdge : source.findOutgoingEdges(e.targetId(), BootstrapIds.HAS_MUTABILITY)) {
                skipEdges.add(mutEdge.id());
            }
        }
    }

    private static void detectSharedValueConflicts(
            MutableGraphState base,
            GraphDiff sourceDiff,
            GraphDiff targetDiff,
            List<MergeConflict> conflicts
    ) {
        Map<EntityId, Change.ValueUpdated> srcUpdates = indexUserValueUpdates(base, sourceDiff.updatedValues());
        Map<EntityId, Change.ValueUpdated> tgtUpdates = indexUserValueUpdates(base, targetDiff.updatedValues());

        for (var entry : srcUpdates.entrySet()) {
            EntityId valId = entry.getKey();
            Change.ValueUpdated tgtUpd = tgtUpdates.get(valId);
            if (tgtUpd != null && !entry.getValue().newValue().equals(tgtUpd.newValue())) {
                conflicts.add(new MergeConflict(
                        MergeConflictType.SHARED_VALUE_MODIFIED,
                        valId,
                        "Shared value " + valId + " modified in both branches: source="
                                + entry.getValue().newValue() + ", target=" + tgtUpd.newValue()
                ));
            }
            if (referencesEntity(targetDiff.addedEdges(), valId)) {
                conflicts.add(new MergeConflict(
                        MergeConflictType.SHARED_VALUE_MODIFIED,
                        valId,
                        "Shared value " + valId + " modified in source while newly referenced in target"
                ));
            }
        }

        for (EntityId valId : tgtUpdates.keySet()) {
            if (!srcUpdates.containsKey(valId) && referencesEntity(sourceDiff.addedEdges(), valId)) {
                conflicts.add(new MergeConflict(
                        MergeConflictType.SHARED_VALUE_MODIFIED,
                        valId,
                        "Shared value " + valId + " modified in target while newly referenced in source"
                ));
            }
        }
    }

    private static Map<EntityId, Change.ValueUpdated> indexUserValueUpdates(
            MutableGraphState base,
            List<Change.ValueUpdated> updates
    ) {
        Map<EntityId, Change.ValueUpdated> map = new HashMap<>();
        for (Change.ValueUpdated upd : updates) {
            EntityNode node = base.entities.get(upd.entityId());
            if (node != null && !node.typeId().isBootstrap()) {
                map.put(upd.entityId(), upd);
            }
        }
        return map;
    }

    private static boolean referencesEntity(List<Edge> edges, EntityId entityId) {
        for (Edge edge : edges) {
            if (edge.sourceId().equals(entityId) || edge.targetId().equals(entityId)) {
                return true;
            }
        }
        return false;
    }

    private static void detectEdgeDeletedAndAnnotatedConflicts(
            GraphDiff sourceDiff,
            GraphDiff targetDiff,
            List<MergeConflict> conflicts
    ) {
        for (Edge removed : sourceDiff.removedEdges()) {
            if (referencesEntity(targetDiff.addedEdges(), removed.id())) {
                conflicts.add(new MergeConflict(
                        MergeConflictType.EDGE_DELETED_AND_ANNOTATED,
                        removed.id(),
                        "Edge " + removed.id() + " deleted in source while annotated in target"
                ));
            }
        }
        for (Edge removed : targetDiff.removedEdges()) {
            if (referencesEntity(sourceDiff.addedEdges(), removed.id())) {
                conflicts.add(new MergeConflict(
                        MergeConflictType.EDGE_DELETED_AND_ANNOTATED,
                        removed.id(),
                        "Edge " + removed.id() + " deleted in target while annotated in source"
                ));
            }
        }
    }

    private static void detectConcurrentFunctionalAttributes(
            MutableGraphState source,
            MutableGraphState target,
            GraphDiff sourceDiff,
            GraphDiff targetDiff,
            List<MergeConflict> conflicts
    ) {
        record SourcePredKey(EntityId sourceId, EntityId predicateId) {
        }
        Map<SourcePredKey, Edge> targetNewEdges = new HashMap<>();
        for (Edge edge : targetDiff.addedEdges()) {
            if (!GraphSchemaValidator.isSchemaStructuralPredicate(edge.predicateId())) {
                targetNewEdges.put(new SourcePredKey(edge.sourceId(), edge.predicateId()), edge);
            }
        }

        for (Edge srcEdge : sourceDiff.addedEdges()) {
            if (GraphSchemaValidator.isSchemaStructuralPredicate(srcEdge.predicateId())) {
                continue;
            }
            Edge tgtEdge = targetNewEdges.get(new SourcePredKey(srcEdge.sourceId(), srcEdge.predicateId()));
            if (tgtEdge == null) {
                continue;
            }
            boolean functionalInTarget = target.resolvePredicateConstraints(srcEdge.predicateId())
                    .map(c -> c.maxOutgoing().isPresent() && c.maxOutgoing().getAsInt() == 1)
                    .orElse(false);
            boolean functionalInSource = source.resolvePredicateConstraints(srcEdge.predicateId())
                    .map(c -> c.maxOutgoing().isPresent() && c.maxOutgoing().getAsInt() == 1)
                    .orElse(false);
            if ((functionalInTarget || functionalInSource) && !srcEdge.targetId().equals(tgtEdge.targetId())) {
                conflicts.add(new MergeConflict(
                        MergeConflictType.CONCURRENT_FUNCTIONAL_ATTRIBUTE,
                        srcEdge.sourceId(),
                        "Concurrent functional assignment on " + srcEdge.sourceId()
                                + " for predicate " + srcEdge.predicateId()
                ));
            }
        }
    }

    private static MutableGraphState applySourceDeltaOntoTarget(
            MutableGraphState targetState,
            GraphDiff sourceDiff,
            Set<EntityId> skipEntities,
            Set<EntityId> skipEdges
    ) {
        MutableGraphState merged = targetState.copy();

        for (EntityNode added : sourceDiff.addedEntities()) {
            if (added.kind() != EntityKind.EDGE && !skipEntities.contains(added.id())) {
                merged.putEntity(added);
            }
        }
        for (Change.ValueUpdated upd : sourceDiff.updatedValues()) {
            EntityNode current = merged.entities.get(upd.entityId());
            if (current != null && current.kind() == EntityKind.VALUE) {
                merged.putEntity(new EntityNode(upd.entityId(), current.typeId(), EntityKind.VALUE, Optional.of(upd.newValue())));
            }
        }
        for (Edge addedEdge : sourceDiff.addedEdges()) {
            if (!skipEdges.contains(addedEdge.id())) {
                merged.putEdge(addedEdge);
            }
        }
        for (Edge removedEdge : sourceDiff.removedEdges()) {
            merged.removeEdge(removedEdge.id());
        }
        for (EntityNode removedEntity : sourceDiff.removedEntities()) {
            if (removedEntity.kind() != EntityKind.EDGE) {
                merged.entities.remove(removedEntity.id());
            }
        }
        merged.rebuildDerivedIndexes();
        return merged;
    }

    private static void validateMergedGraph(MutableGraphState merged, List<MergeConflict> conflicts) {
        for (Edge edge : merged.edges.values()) {
            EntityNode src = merged.entities.get(edge.sourceId());
            EntityNode pred = merged.entities.get(edge.predicateId());
            EntityNode tgt = merged.entities.get(edge.targetId());
            if (src == null || pred == null || tgt == null) {
                conflicts.add(new MergeConflict(
                        MergeConflictType.SCHEMA_CONSTRAINT_DATA_INCOMPATIBLE,
                        edge.id(),
                        "Merged edge " + edge.id() + " references missing endpoint or predicate"
                ));
                continue;
            }
            try {
                EntityId domain = GraphSchemaValidator.resolveSingleSchemaTarget(merged, pred.id(), BootstrapIds.HAS_DOMAIN);
                EntityId range = GraphSchemaValidator.resolveSingleSchemaTarget(merged, pred.id(), BootstrapIds.HAS_RANGE);
                if (!GraphSchemaValidator.matchesTypeConstraint(src, domain)
                        || !GraphSchemaValidator.matchesTypeConstraint(tgt, range)) {
                    conflicts.add(new MergeConflict(
                            MergeConflictType.SCHEMA_CONSTRAINT_DATA_INCOMPATIBLE,
                            edge.id(),
                            "Merged edge " + edge.id() + " violates domain/range constraint"
                    ));
                }
            } catch (SchemaViolationException ex) {
                conflicts.add(new MergeConflict(
                        MergeConflictType.SCHEMA_CONSTRAINT_DATA_INCOMPATIBLE,
                        edge.id(),
                        ex.getMessage()
                ));
            }
        }

        for (EntityNode node : merged.entities.values()) {
            if (node.kind() == EntityKind.META_TYPE && BootstrapIds.META_PREDICATE_TYPE.equals(node.typeId())) {
                PredicateConstraints constraints = merged.resolvePredicateConstraints(node.id())
                        .orElse(PredicateConstraints.unconstrained());
                try {
                    GraphSchemaValidator.validateExistingEdgesForConstraints(merged, node.id(), constraints);
                } catch (SchemaViolationException ex) {
                    conflicts.add(new MergeConflict(
                            MergeConflictType.SCHEMA_CONSTRAINT_DATA_INCOMPATIBLE,
                            node.id(),
                            ex.getMessage()
                    ));
                }
            } else if (node.kind() == EntityKind.META_TYPE
                    && BootstrapIds.META_VALUE_TYPE.equals(node.typeId())
                    && merged.isUniqueValueType(node.id())) {
                try {
                    GraphSchemaValidator.buildUniqueValueIndexForExistingInstances(merged, node.id());
                } catch (SchemaViolationException ex) {
                    conflicts.add(new MergeConflict(
                            MergeConflictType.SCHEMA_CONSTRAINT_DATA_INCOMPATIBLE,
                            node.id(),
                            ex.getMessage()
                    ));
                }
            }
        }
    }

    private static List<Change> computeChangesFromTarget(
            CommitId targetId,
            InMemoryGraphSnapshot targetSnap,
            MutableGraphState merged
    ) {
        GraphDiff delta = GraphDiffCalculator.compute(targetId, targetSnap, targetId, new InMemoryGraphSnapshot(merged));
        List<Change> changes = new ArrayList<>();
        for (EntityNode added : delta.addedEntities()) {
            if (added.kind() != EntityKind.EDGE) {
                changes.add(new Change.EntityCreated(added));
            }
        }
        changes.addAll(delta.updatedValues());
        for (Edge addedEdge : delta.addedEdges()) {
            changes.add(new Change.EdgeConnected(addedEdge));
        }
        for (Edge removedEdge : delta.removedEdges()) {
            changes.add(new Change.EdgeDisconnected(removedEdge));
        }
        for (EntityNode removed : delta.removedEntities()) {
            if (removed.kind() != EntityKind.EDGE) {
                changes.add(new Change.EntityRemoved(removed));
            }
        }
        return changes;
    }

    private static Map<CommitId, Integer> computeAncestorDistances(List<Commit> commits, CommitId startId) {
        Map<CommitId, Integer> distances = new HashMap<>();
        Queue<CommitId> queue = new ArrayDeque<>();
        distances.put(startId, 0);
        queue.add(startId);

        while (!queue.isEmpty()) {
            CommitId currentId = queue.poll();
            int nextDist = distances.get(currentId) + 1;
            Commit current = commits.get((int) currentId.value());
            for (CommitId parent : current.parentIds()) {
                if (!distances.containsKey(parent)) {
                    distances.put(parent, nextDist);
                    queue.add(parent);
                }
            }
        }
        return distances;
    }
}
