package io.veg.fixtures;

import io.veg.engine.CardinalityViolationException;
import io.veg.engine.GraphSession;
import io.veg.engine.ImmutableValueModificationException;
import io.veg.engine.ReferentialIntegrityException;
import io.veg.engine.TypeMismatchException;
import io.veg.engine.UniquenessViolationException;
import io.veg.model.BootstrapIds;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.PredicateConstraints;
import io.veg.model.Value;
import io.veg.model.ValueMutability;
import io.veg.model.ValueType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend-agnostic Stage 2 contract tests covering identity vs. value equality,
 * shared mutation semantics, unique value types, predicate cardinality, and semantic assertions.
 */
public abstract class Stage2ContractTestSuite {

    protected abstract GraphSession createSession();

    @Test
    void identityIsSeparateFromValueEqualityForNonSharedValues() {
        GraphSession graph = createSession();
        EntityId personType = graph.defineObjectType("Person");
        EntityId firstNameType = graph.defineValueType("FirstName", ValueType.STRING);
        EntityId hasFirstName = graph.definePredicate("hasFirstName", personType, firstNameType);

        EntityId p1 = graph.createObject(personType);
        EntityId p2 = graph.createObject(personType);

        EntityId thomas1 = graph.createValue(firstNameType, Value.of("Thomas"));
        EntityId thomas2 = graph.createValue(firstNameType, Value.of("Thomas"));

        assertThat(thomas1).isNotEqualTo(thomas2);
        assertThat(graph.findEntity(thomas1).orElseThrow().payload())
                .isEqualTo(graph.findEntity(thomas2).orElseThrow().payload());

        graph.connect(p1, hasFirstName, thomas1);
        graph.connect(p2, hasFirstName, thomas2);

        assertThat(graph.findOutgoingEdges(p1, hasFirstName)).singleElement().extracting(Edge::targetId).isEqualTo(thomas1);
        assertThat(graph.findOutgoingEdges(p2, hasFirstName)).singleElement().extracting(Edge::targetId).isEqualTo(thomas2);
    }

    @Test
    void immutableSharedValueRejectsUpdateWhileSharedMutablePropagatesToAllReferents() {
        GraphSession graph = createSession();
        EntityId personType = graph.defineObjectType("Person");
        EntityId firstNameType = graph.defineValueType("FirstName", ValueType.STRING);
        EntityId companyNameType = graph.defineValueType("CompanyName", ValueType.STRING);
        EntityId hasFirstName = graph.definePredicate("hasFirstName", personType, firstNameType);
        EntityId worksAtCompany = graph.definePredicate("worksAtCompany", personType, companyNameType);

        EntityId p1 = graph.createObject(personType);
        EntityId p2 = graph.createObject(personType);

        // Default IMMUTABLE value shared by two persons
        EntityId immutableThomas = graph.createValue(firstNameType, Value.of("Thomas"));
        assertThat(graph.resolveValueMutability(immutableThomas)).contains(ValueMutability.IMMUTABLE);

        EntityId p1NameEdge = graph.connect(p1, hasFirstName, immutableThomas);
        graph.connect(p2, hasFirstName, immutableThomas);

        assertThatThrownBy(() -> graph.updateValue(immutableThomas, Value.of("Tom")))
                .isInstanceOf(ImmutableValueModificationException.class);
        assertThat(graph.findEntity(immutableThomas).orElseThrow().payload()).contains(Value.of("Thomas"));

        // Changing p1's immutable attribute requires replacing p1's edge without affecting p2
        graph.disconnect(p1NameEdge);
        EntityId tom = graph.createValue(firstNameType, Value.of("Tom"));
        graph.connect(p1, hasFirstName, tom);
        assertThat(graph.findEntity(graph.findOutgoingEdges(p2, hasFirstName).getFirst().targetId()).orElseThrow().payload())
                .contains(Value.of("Thomas"));

        // Explicit SHARED_MUTABLE value shared by two persons
        EntityId sharedCompany = graph.createValue(companyNameType, Value.of("Acme GmbH"), ValueMutability.SHARED_MUTABLE);
        assertThat(graph.resolveValueMutability(sharedCompany)).contains(ValueMutability.SHARED_MUTABLE);
        graph.connect(p1, worksAtCompany, sharedCompany);
        graph.connect(p2, worksAtCompany, sharedCompany);

        // Wrong scalar type on updateValue is rejected atomically
        assertThatThrownBy(() -> graph.updateValue(sharedCompany, Value.of(999L)))
                .isInstanceOf(TypeMismatchException.class);
        assertThat(graph.findEntity(sharedCompany).orElseThrow().payload()).contains(Value.of("Acme GmbH"));

        // Valid update immediately affects both referents
        graph.updateValue(sharedCompany, Value.of("Acme AG"));
        assertThat(graph.findEntity(graph.findOutgoingEdges(p1, worksAtCompany).getFirst().targetId()).orElseThrow().payload())
                .contains(Value.of("Acme AG"));
        assertThat(graph.findEntity(graph.findOutgoingEdges(p2, worksAtCompany).getFirst().targetId()).orElseThrow().payload())
                .contains(Value.of("Acme AG"));

        // Internal schema name value nodes remain immutable
        Edge typeNameEdge = graph.findOutgoingEdges(personType, BootstrapIds.HAS_NAME).getFirst();
        assertThatThrownBy(() -> graph.updateValue(typeNameEdge.targetId(), Value.of("HackedPerson")))
                .isInstanceOf(ImmutableValueModificationException.class);
    }

    @Test
    void uniqueValueTypeEnforcesUniquenessAcrossCreateAndUpdate() {
        GraphSession graph = createSession();
        EntityId emailType = graph.defineValueType("EmailAddress", ValueType.STRING, true);
        EntityId tagType = graph.defineValueType("Tag", ValueType.STRING, false);

        assertThat(graph.isUniqueValueType(emailType)).isTrue();
        assertThat(graph.isUniqueValueType(tagType)).isFalse();

        EntityId aliceEmail = graph.createValue(emailType, Value.of("alice@example.com"), ValueMutability.SHARED_MUTABLE);
        EntityId bobEmail = graph.createValue(emailType, Value.of("bob@example.com"), ValueMutability.SHARED_MUTABLE);

        long entitiesBefore = graph.entityCount();
        long edgesBefore = graph.edgeCount();

        // Duplicate create fails atomically
        assertThatThrownBy(() -> graph.createValue(emailType, Value.of("alice@example.com")))
                .isInstanceOf(UniquenessViolationException.class);
        assertThat(graph.entityCount()).isEqualTo(entitiesBefore);
        assertThat(graph.edgeCount()).isEqualTo(edgesBefore);

        // Duplicate update fails atomically
        assertThatThrownBy(() -> graph.updateValue(bobEmail, Value.of("alice@example.com")))
                .isInstanceOf(UniquenessViolationException.class);
        assertThat(graph.findEntity(bobEmail).orElseThrow().payload()).contains(Value.of("bob@example.com"));

        // Idempotent update with own current value succeeds
        graph.updateValue(aliceEmail, Value.of("alice@example.com"));

        // Updating to a free value succeeds and releases the old value
        graph.updateValue(bobEmail, Value.of("robert@example.com"));
        EntityId recycledBob = graph.createValue(emailType, Value.of("bob@example.com"));
        assertThat(graph.findEntity(recycledBob).orElseThrow().payload()).contains(Value.of("bob@example.com"));
    }

    @Test
    void predicateConstraintsEnforceManyToOneOneToManyOneToOneAndUniqueSet() {
        GraphSession graph = createSession();
        EntityId personType = graph.defineObjectType("Person");
        EntityId passportType = graph.defineObjectType("Passport");
        EntityId orderType = graph.defineObjectType("Order");
        EntityId orderItemType = graph.defineObjectType("OrderItem");
        EntityId groupType = graph.defineObjectType("Group");
        EntityId birthDateType = graph.defineValueType("BirthDate", ValueType.TIMESTAMP);

        EntityId hasBirthDate = graph.definePredicate("hasBirthDate", personType, birthDateType, PredicateConstraints.manyToOne());
        EntityId containsItem = graph.definePredicate("containsItem", orderType, orderItemType, PredicateConstraints.oneToMany());
        EntityId hasPassport = graph.definePredicate("hasPassport", personType, passportType, PredicateConstraints.oneToOne());
        EntityId memberOf = graph.definePredicate("memberOf", personType, groupType, PredicateConstraints.uniqueSet());

        assertThat(graph.resolvePredicateConstraints(hasBirthDate)).contains(PredicateConstraints.manyToOne());
        assertThat(graph.resolvePredicateConstraints(containsItem)).contains(PredicateConstraints.oneToMany());
        assertThat(graph.resolvePredicateConstraints(hasPassport)).contains(PredicateConstraints.oneToOne());
        assertThat(graph.resolvePredicateConstraints(memberOf)).contains(PredicateConstraints.uniqueSet());

        // manyToOne: maxOutgoing = 1
        EntityId p1 = graph.createObject(personType);
        EntityId p2 = graph.createObject(personType);
        EntityId bd1 = graph.createValue(birthDateType, Value.of(Instant.parse("1990-01-01T00:00:00Z")));
        EntityId bd2 = graph.createValue(birthDateType, Value.of(Instant.parse("1991-02-02T00:00:00Z")));

        EntityId bdEdge1 = graph.connect(p1, hasBirthDate, bd1);
        assertThatThrownBy(() -> graph.connect(p1, hasBirthDate, bd2))
                .isInstanceOf(CardinalityViolationException.class);
        graph.disconnect(bdEdge1);
        graph.connect(p1, hasBirthDate, bd2);

        // oneToMany: maxIncoming = 1
        EntityId order1 = graph.createObject(orderType);
        EntityId order2 = graph.createObject(orderType);
        EntityId item1 = graph.createObject(orderItemType);
        EntityId item2 = graph.createObject(orderItemType);
        graph.connect(order1, containsItem, item1);
        graph.connect(order1, containsItem, item2);
        assertThatThrownBy(() -> graph.connect(order2, containsItem, item1))
                .isInstanceOf(CardinalityViolationException.class);

        // oneToOne: maxOutgoing = 1 and maxIncoming = 1
        EntityId pass1 = graph.createObject(passportType);
        EntityId pass2 = graph.createObject(passportType);
        graph.connect(p1, hasPassport, pass1);
        assertThatThrownBy(() -> graph.connect(p1, hasPassport, pass2))
                .isInstanceOf(CardinalityViolationException.class);
        assertThatThrownBy(() -> graph.connect(p2, hasPassport, pass1))
                .isInstanceOf(CardinalityViolationException.class);

        // uniqueSet: N:M allowed, duplicate identical triple rejected
        EntityId g1 = graph.createObject(groupType);
        EntityId g2 = graph.createObject(groupType);
        graph.connect(p1, memberOf, g1);
        graph.connect(p1, memberOf, g2);
        graph.connect(p2, memberOf, g1);
        assertThatThrownBy(() -> graph.connect(p1, memberOf, g1))
                .isInstanceOf(UniquenessViolationException.class);
    }

    @Test
    void semanticAssertionsSupportMultiplePredicatesWeightConfidenceCompetingSourcesAndSecondOrderChains() {
        GraphSession graph = createSession();
        EntityId personType = graph.defineObjectType("Person");
        EntityId projectType = graph.defineObjectType("Project");
        EntityId docType = graph.defineObjectType("SourceDocument");
        EntityId weightType = graph.defineValueType("Weight", ValueType.DECIMAL);
        EntityId confidenceType = graph.defineValueType("Confidence", ValueType.DECIMAL);

        EntityId worksOn = graph.definePredicate("worksOn", personType, projectType);
        EntityId contributesTo = graph.definePredicate("contributesTo", personType, projectType);
        EntityId hasWeight = graph.definePredicate("hasWeight", BootstrapIds.ANY_EDGE, weightType, PredicateConstraints.manyToOne());
        EntityId hasConfidence = graph.definePredicate("hasConfidence", BootstrapIds.ANY_EDGE, confidenceType, PredicateConstraints.manyToOne());
        EntityId supportedBy = graph.definePredicate("supportedBy", BootstrapIds.ANY_EDGE, docType);
        EntityId reviewedBy = graph.definePredicate("reviewedBy", BootstrapIds.ANY_EDGE, personType);

        EntityId alice = graph.createObject(personType);
        EntityId auditor = graph.createObject(personType);
        EntityId project = graph.createObject(projectType);
        EntityId docA = graph.createObject(docType);
        EntityId docB = graph.createObject(docType);
        EntityId auditReport = graph.createObject(docType);

        // Multiple semantic predicates between the exact same (alice, project) pair (M-10)
        EntityId worksOnEdge = graph.connect(alice, worksOn, project);
        EntityId contribFromDocA = graph.connect(alice, contributesTo, project);
        EntityId contribFromDocB = graph.connect(alice, contributesTo, project);

        assertThat(worksOnEdge).isNotEqualTo(contribFromDocA);
        assertThat(contribFromDocA).isNotEqualTo(contribFromDocB);

        // Distinguish weight (strength) vs. confidence (certainty) and provenance per assertion (M-11, S-07)
        EntityId wA = graph.createValue(weightType, Value.of(new BigDecimal("0.80")));
        EntityId cA = graph.createValue(confidenceType, Value.of(new BigDecimal("0.95")));
        EntityId supA = graph.connect(contribFromDocA, supportedBy, docA);
        EntityId wEdgeA = graph.connect(contribFromDocA, hasWeight, wA);
        EntityId cEdgeA = graph.connect(contribFromDocA, hasConfidence, cA);

        EntityId wB = graph.createValue(weightType, Value.of(new BigDecimal("0.30")));
        EntityId cB = graph.createValue(confidenceType, Value.of(new BigDecimal("0.40")));
        EntityId supB = graph.connect(contribFromDocB, supportedBy, docB);
        EntityId wEdgeB = graph.connect(contribFromDocB, hasWeight, wB);
        EntityId cEdgeB = graph.connect(contribFromDocB, hasConfidence, cB);

        // 2nd-order relation-to-relation chain: worksOnEdge -> reviewedBy -> auditReport
        EntityId reviewEdge = graph.connect(worksOnEdge, reviewedBy, auditor);
        EntityId reviewDocEdge = graph.connect(reviewEdge, supportedBy, auditReport);

        List<Edge> contribAssertions = graph.findOutgoingEdges(alice, contributesTo);
        assertThat(contribAssertions).hasSize(2);
        assertThat(graph.findOutgoingEdges(contribFromDocA, hasWeight)).singleElement().extracting(Edge::targetId).isEqualTo(wA);
        assertThat(graph.findOutgoingEdges(contribFromDocA, hasConfidence)).singleElement().extracting(Edge::targetId).isEqualTo(cA);
        assertThat(graph.findOutgoingEdges(contribFromDocB, supportedBy)).singleElement().extracting(Edge::targetId).isEqualTo(docB);

        // Removing contributesTo assertions leaves worksOn intact
        graph.disconnect(supA);
        graph.disconnect(wEdgeA);
        graph.disconnect(cEdgeA);
        graph.disconnect(contribFromDocA);

        graph.disconnect(supB);
        graph.disconnect(wEdgeB);
        graph.disconnect(cEdgeB);
        graph.disconnect(contribFromDocB);

        assertThat(graph.findOutgoingEdges(alice, contributesTo)).isEmpty();
        assertThat(graph.findOutgoingEdges(alice, worksOn)).containsExactly(new Edge(worksOnEdge, alice, worksOn, project));

        // Referential integrity spans multi-hop edge-to-edge chains
        assertThatThrownBy(() -> graph.disconnect(worksOnEdge))
                .isInstanceOf(ReferentialIntegrityException.class);
        assertThatThrownBy(() -> graph.disconnect(reviewEdge))
                .isInstanceOf(ReferentialIntegrityException.class);

        graph.disconnect(reviewDocEdge);
        graph.disconnect(reviewEdge);
        graph.disconnect(worksOnEdge);
        assertThat(graph.findEdge(worksOnEdge)).isEmpty();
    }
}
