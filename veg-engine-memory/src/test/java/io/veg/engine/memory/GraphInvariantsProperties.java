package io.veg.engine.memory;

import io.veg.engine.DomainRangeViolationException;
import io.veg.engine.TypeMismatchException;
import io.veg.model.BootstrapIds;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.Value;
import io.veg.model.ValueType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests verifying graph invariants I-01..I-05 under randomized valid and invalid mutations.
 */
class GraphInvariantsProperties {

    @Property(tries = 40)
    void randomValidAndInvalidOperationsPreserveGraphInvariants(@ForAll("seeds") long seed) {
        InMemoryGraphEngine engine = new InMemoryGraphEngine();

        EntityId personType = engine.defineObjectType("Person");
        EntityId docType = engine.defineObjectType("Document");
        EntityId nameType = engine.defineValueType("Name", ValueType.STRING);
        EntityId scoreType = engine.defineValueType("Score", ValueType.INT);

        EntityId hasName = engine.definePredicate("hasName", personType, nameType);
        EntityId hasScore = engine.definePredicate("hasScore", personType, scoreType);
        EntityId supportedBy = engine.definePredicate("supportedBy", BootstrapIds.ANY_EDGE, docType);

        List<EntityId> persons = new ArrayList<>();
        List<EntityId> docs = new ArrayList<>();
        List<EntityId> names = new ArrayList<>();
        List<EntityId> scores = new ArrayList<>();
        List<EntityId> primaryEdges = new ArrayList<>();
        List<EntityId> metaEdges = new ArrayList<>();

        Random random = new Random(seed);

        for (int step = 0; step < 80; step++) {
            int action = random.nextInt(7);
            switch (action) {
                case 0 -> persons.add(engine.createObject(personType));
                case 1 -> docs.add(engine.createObject(docType));
                case 2 -> {
                    names.add(engine.createValue(nameType, Value.of("Name-" + step)));
                    // Invalid scalar type must fail atomically without changing counts
                    long entitiesBefore = engine.entityCount();
                    long edgesBefore = engine.edgeCount();
                    assertThatThrownBy(() -> engine.createValue(nameType, Value.of(123L)))
                            .isInstanceOf(TypeMismatchException.class);
                    assertThat(engine.entityCount()).isEqualTo(entitiesBefore);
                    assertThat(engine.edgeCount()).isEqualTo(edgesBefore);
                }
                case 3 -> scores.add(engine.createValue(scoreType, Value.of((long) step)));
                case 4 -> {
                    if (!persons.isEmpty() && !names.isEmpty()) {
                        EntityId p = persons.get(random.nextInt(persons.size()));
                        EntityId n = names.get(random.nextInt(names.size()));
                        primaryEdges.add(engine.connect(p, hasName, n));
                    }
                    // Invalid domain/range must fail atomically
                    if (!persons.isEmpty() && !scores.isEmpty()) {
                        EntityId p = persons.get(random.nextInt(persons.size()));
                        EntityId s = scores.get(random.nextInt(scores.size()));
                        long entitiesBefore = engine.entityCount();
                        long edgesBefore = engine.edgeCount();
                        assertThatThrownBy(() -> engine.connect(p, hasName, s))
                                .isInstanceOf(DomainRangeViolationException.class);
                        assertThat(engine.entityCount()).isEqualTo(entitiesBefore);
                        assertThat(engine.edgeCount()).isEqualTo(edgesBefore);
                    }
                }
                case 5 -> {
                    if (!primaryEdges.isEmpty() && !docs.isEmpty()) {
                        EntityId e = primaryEdges.get(random.nextInt(primaryEdges.size()));
                        EntityId d = docs.get(random.nextInt(docs.size()));
                        metaEdges.add(engine.connect(e, supportedBy, d));
                    }
                }
                case 6 -> {
                    if (!metaEdges.isEmpty()) {
                        EntityId me = metaEdges.removeLast();
                        engine.disconnect(me);
                        assertThat(engine.findEdge(me)).isEmpty();
                        assertThat(engine.findEntity(me)).isEmpty();
                    }
                }
                default -> {
                }
            }
        }

        // Verify structural invariants across all live entities and edges
        for (EntityId eId : primaryEdges) {
            Edge edge = engine.findEdge(eId).orElseThrow();
            assertThat(engine.findEntity(edge.id()))
                    .contains(new EntityNode(edge.id(), BootstrapIds.META_EDGE_TYPE, EntityKind.EDGE, java.util.Optional.empty()));
            assertThat(engine.findEntity(edge.sourceId())).isPresent();
            assertThat(engine.findEntity(edge.targetId())).isPresent();
            assertThat(engine.findOutgoingEdges(edge.sourceId(), hasName)).contains(edge);
            assertThat(engine.findIncomingEdges(edge.targetId(), hasName)).contains(edge);
        }

        for (EntityId meId : metaEdges) {
            Edge edge = engine.findEdge(meId).orElseThrow();
            assertThat(engine.findEntity(edge.sourceId())).get().extracting(EntityNode::kind).isEqualTo(EntityKind.EDGE);
            assertThat(engine.findEntity(edge.targetId())).get().extracting(EntityNode::typeId).isEqualTo(docType);
        }

        // Check hasScore can connect persons and scores cleanly too
        if (!persons.isEmpty() && !scores.isEmpty()) {
            EntityId scoreEdge = engine.connect(persons.getFirst(), hasScore, scores.getFirst());
            assertThat(engine.findEdge(scoreEdge)).isPresent();
        }
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs().between(1L, 100_000L);
    }
}
