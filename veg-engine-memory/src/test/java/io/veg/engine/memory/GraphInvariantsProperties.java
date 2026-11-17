package io.veg.engine.memory;

import io.veg.engine.CardinalityViolationException;
import io.veg.engine.DomainRangeViolationException;
import io.veg.engine.ImmutableValueModificationException;
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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests verifying graph invariants I-01..I-10 under randomized valid and invalid mutations.
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

    @Property(tries = 40)
    void stage2MutabilityUniquenessAndCardinalityPreserveInvariants(@ForAll("seeds") long seed) {
        InMemoryGraphEngine engine = new InMemoryGraphEngine();

        EntityId personType = engine.defineObjectType("Person");
        EntityId badgeType = engine.defineObjectType("Badge");
        EntityId emailType = engine.defineValueType("Email", ValueType.STRING, true);
        EntityId nicknameType = engine.defineValueType("Nickname", ValueType.STRING, false);

        EntityId hasBadge = engine.definePredicate("hasBadge", personType, badgeType, PredicateConstraints.oneToOne());
        EntityId hasEmail = engine.definePredicate("hasEmail", personType, emailType, PredicateConstraints.manyToOne());

        List<EntityId> persons = new ArrayList<>();
        List<EntityId> badges = new ArrayList<>();
        List<EntityId> immutableNicknames = new ArrayList<>();
        List<EntityId> mutableEmails = new ArrayList<>();
        Set<String> activeEmails = new HashSet<>();
        Map<EntityId, EntityId> personToBadge = new HashMap<>();
        Set<EntityId> assignedBadges = new HashSet<>();

        Random random = new Random(seed);
        for (int step = 0; step < 90; step++) {
            int op = random.nextInt(6);
            switch (op) {
                case 0 -> persons.add(engine.createObject(personType));
                case 1 -> badges.add(engine.createObject(badgeType));
                case 2 -> immutableNicknames.add(engine.createValue(nicknameType, Value.of("Nick-" + step)));
                case 3 -> {
                    String candidate = "user" + random.nextInt(25) + "@veg.io";
                    if (activeEmails.contains(candidate)) {
                        long entitiesBefore = engine.entityCount();
                        assertThatThrownBy(() -> engine.createValue(emailType, Value.of(candidate), ValueMutability.SHARED_MUTABLE))
                                .isInstanceOf(UniquenessViolationException.class);
                        assertThat(engine.entityCount()).isEqualTo(entitiesBefore);
                    } else {
                        mutableEmails.add(engine.createValue(emailType, Value.of(candidate), ValueMutability.SHARED_MUTABLE));
                        activeEmails.add(candidate);
                    }
                }
                case 4 -> {
                    if (!immutableNicknames.isEmpty()) {
                        EntityId imm = immutableNicknames.get(random.nextInt(immutableNicknames.size()));
                        Value before = engine.findEntity(imm).orElseThrow().payload().orElseThrow();
                        assertThatThrownBy(() -> engine.updateValue(imm, Value.of("Mutated")))
                                .isInstanceOf(ImmutableValueModificationException.class);
                        assertThat(engine.findEntity(imm).orElseThrow().payload()).contains(before);
                    }
                    if (!mutableEmails.isEmpty()) {
                        EntityId emailId = mutableEmails.get(random.nextInt(mutableEmails.size()));
                        String current = ((Value.StringValue) engine.findEntity(emailId).orElseThrow().payload().orElseThrow()).value();
                        String target = "user" + random.nextInt(25) + "@veg.io";
                        if (activeEmails.contains(target) && !target.equals(current)) {
                            assertThatThrownBy(() -> engine.updateValue(emailId, Value.of(target)))
                                    .isInstanceOf(UniquenessViolationException.class);
                            assertThat(engine.findEntity(emailId).orElseThrow().payload()).contains(Value.of(current));
                        } else {
                            engine.updateValue(emailId, Value.of(target));
                            activeEmails.remove(current);
                            activeEmails.add(target);
                            assertThat(engine.findEntity(emailId).orElseThrow().payload()).contains(Value.of(target));
                        }
                    }
                }
                case 5 -> {
                    if (!persons.isEmpty() && !badges.isEmpty()) {
                        EntityId p = persons.get(random.nextInt(persons.size()));
                        EntityId b = badges.get(random.nextInt(badges.size()));
                        if (personToBadge.containsKey(p) || assignedBadges.contains(b)) {
                            long edgesBefore = engine.edgeCount();
                            assertThatThrownBy(() -> engine.connect(p, hasBadge, b))
                                    .isInstanceOfAny(CardinalityViolationException.class, UniquenessViolationException.class);
                            assertThat(engine.edgeCount()).isEqualTo(edgesBefore);
                        } else {
                            engine.connect(p, hasBadge, b);
                            personToBadge.put(p, b);
                            assignedBadges.add(b);
                        }
                    }
                }
                default -> {
                }
            }
        }

        // Verify 1:1 and manyToOne invariants hold for every person and badge
        for (EntityId p : persons) {
            assertThat(engine.findOutgoingEdges(p, hasBadge).size()).isLessThanOrEqualTo(1);
            assertThat(engine.findOutgoingEdges(p, hasEmail).size()).isLessThanOrEqualTo(1);
        }
        for (EntityId b : badges) {
            assertThat(engine.findIncomingEdges(b, hasBadge).size()).isLessThanOrEqualTo(1);
        }
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs().between(1L, 100_000L);
    }
}
