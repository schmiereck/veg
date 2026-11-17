package io.veg.engine.memory;

import io.veg.engine.GraphReader;
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
 * {@code HAS_SUPER_PREDICATE}) purely at query time against any {@link GraphReader}.
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
            GraphReader reader,
            EntityId sourceId,
            EntityId predicateFilter
    ) {
        Set<DerivedAssertion> derived = new LinkedHashSet<>();

        for (Edge outgoing : reader.findOutgoingEdges(sourceId)) {
            for (EntityId superPred : resolveSuperPredicates(reader, outgoing.predicateId())) {
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

        for (Edge incoming : reader.findIncomingEdges(sourceId)) {
            for (EntityId inversePred : resolveInversePredicates(reader, incoming.predicateId())) {
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
                for (EntityId superOfInverse : resolveSuperPredicates(reader, inversePred)) {
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
            GraphReader reader,
            EntityId sourceId,
            EntityId predicateFilter
    ) {
        List<DerivedAssertion> all = new ArrayList<>();
        List<Edge> explicitEdges = predicateFilter == null
                ? reader.findOutgoingEdges(sourceId)
                : reader.findOutgoingEdges(sourceId, predicateFilter);
        for (Edge edge : explicitEdges) {
            all.add(DerivedAssertion.explicit(edge));
        }
        all.addAll(findDerivedOutgoingAssertions(reader, sourceId, predicateFilter));
        all.sort(DETERMINISTIC_ORDER);
        return List.copyOf(all);
    }

    private static Set<EntityId> resolveInversePredicates(GraphReader reader, EntityId predicateId) {
        Set<EntityId> inverses = new LinkedHashSet<>();
        for (Edge e : reader.findOutgoingEdges(predicateId, BootstrapIds.HAS_INVERSE_PREDICATE)) {
            inverses.add(e.targetId());
        }
        for (Edge e : reader.findIncomingEdges(predicateId, BootstrapIds.HAS_INVERSE_PREDICATE)) {
            inverses.add(e.sourceId());
        }
        return inverses;
    }

    private static Set<EntityId> resolveSuperPredicates(GraphReader reader, EntityId predicateId) {
        Set<EntityId> visited = new HashSet<>();
        visited.add(predicateId);
        Set<EntityId> supers = new LinkedHashSet<>();
        Deque<EntityId> queue = new ArrayDeque<>();
        queue.add(predicateId);

        while (!queue.isEmpty()) {
            EntityId current = queue.poll();
            for (Edge edge : reader.findOutgoingEdges(current, BootstrapIds.HAS_SUPER_PREDICATE)) {
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
