package io.veg.engine.memory;

import io.veg.model.AssertionOrigin;
import io.veg.model.BootstrapIds;
import io.veg.model.DerivedAssertion;
import io.veg.model.Edge;
import io.veg.model.EntityId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Evaluates minimal semantic predicate rules ({@code HAS_INVERSE_PREDICATE} and transitive
 * {@code HAS_SUPER_PREDICATE}) purely at query time so explicit stored edges remain clean and minimal.
 */
final class SemanticInferenceEngine {

    private static final Comparator<DerivedAssertion> DETERMINISTIC_ORDER = Comparator
            .comparing(DerivedAssertion::predicateId)
            .thenComparing(DerivedAssertion::targetId)
            .thenComparing(DerivedAssertion::origin)
            .thenComparing(a -> a.backingEdgeId().orElse(new EntityId(0L)));

    private SemanticInferenceEngine() {
    }

    static List<DerivedAssertion> findDerivedOutgoingAssertions(
            MutableGraphState state,
            EntityId sourceId,
            EntityId predicateFilter
    ) {
        Set<DerivedAssertion> derived = new LinkedHashSet<>();

        for (Edge outgoing : state.findOutgoingEdges(sourceId)) {
            for (EntityId superPred : resolveSuperPredicates(state, outgoing.predicateId())) {
                if (predicateFilter == null || superPred.equals(predicateFilter)) {
                    derived.add(DerivedAssertion.derived(
                            sourceId,
                            superPred,
                            outgoing.targetId(),
                            AssertionOrigin.DERIVED_SUPER_PREDICATE,
                            outgoing.id(),
                            outgoing.predicateId()
                    ));
                }
            }
        }

        for (Edge incoming : state.findIncomingEdges(sourceId)) {
            for (EntityId inversePred : resolveInversePredicates(state, incoming.predicateId())) {
                if (predicateFilter == null || inversePred.equals(predicateFilter)) {
                    derived.add(DerivedAssertion.derived(
                            sourceId,
                            inversePred,
                            incoming.sourceId(),
                            AssertionOrigin.DERIVED_INVERSE,
                            incoming.id(),
                            incoming.predicateId()
                    ));
                }
                for (EntityId superOfInverse : resolveSuperPredicates(state, inversePred)) {
                    if (predicateFilter == null || superOfInverse.equals(predicateFilter)) {
                        derived.add(DerivedAssertion.derived(
                                sourceId,
                                superOfInverse,
                                incoming.sourceId(),
                                AssertionOrigin.DERIVED_SUPER_PREDICATE,
                                incoming.id(),
                                incoming.predicateId()
                        ));
                    }
                }
            }
        }

        List<DerivedAssertion> sorted = new ArrayList<>(derived);
        sorted.sort(DETERMINISTIC_ORDER);
        return List.copyOf(sorted);
    }

    static List<DerivedAssertion> findAllOutgoingAssertions(
            MutableGraphState state,
            EntityId sourceId,
            EntityId predicateFilter
    ) {
        List<DerivedAssertion> all = new ArrayList<>();
        List<Edge> explicitEdges = predicateFilter == null
                ? state.findOutgoingEdges(sourceId)
                : state.findOutgoingEdges(sourceId, predicateFilter);
        for (Edge edge : explicitEdges) {
            all.add(DerivedAssertion.explicit(edge));
        }
        all.addAll(findDerivedOutgoingAssertions(state, sourceId, predicateFilter));
        all.sort(DETERMINISTIC_ORDER);
        return List.copyOf(all);
    }

    private static Set<EntityId> resolveInversePredicates(MutableGraphState state, EntityId predicateId) {
        Set<EntityId> inverses = new LinkedHashSet<>();
        for (Edge e : state.findOutgoingEdges(predicateId, BootstrapIds.HAS_INVERSE_PREDICATE)) {
            inverses.add(e.targetId());
        }
        for (Edge e : state.findIncomingEdges(predicateId, BootstrapIds.HAS_INVERSE_PREDICATE)) {
            inverses.add(e.sourceId());
        }
        return inverses;
    }

    private static Set<EntityId> resolveSuperPredicates(MutableGraphState state, EntityId predicateId) {
        Set<EntityId> visited = new HashSet<>();
        visited.add(predicateId);
        Set<EntityId> supers = new LinkedHashSet<>();
        Deque<EntityId> queue = new ArrayDeque<>();
        queue.add(predicateId);

        while (!queue.isEmpty()) {
            EntityId current = queue.poll();
            for (Edge edge : state.findOutgoingEdges(current, BootstrapIds.HAS_SUPER_PREDICATE)) {
                EntityId superPred = edge.targetId();
                if (visited.add(superPred)) {
                    supers.add(superPred);
                    queue.add(superPred);
                }
            }
        }
        return supers;
    }
}
