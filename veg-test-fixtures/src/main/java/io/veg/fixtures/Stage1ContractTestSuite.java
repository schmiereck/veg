package io.veg.fixtures;

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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend-agnostic Stage 1 contract tests covering M-01..M-05 and scenarios T01..T04.
 *
 * <p>Every storage engine implementation must pass this identical suite to preserve P-01 invariants.</p>
 */
public abstract class Stage1ContractTestSuite {

    protected abstract GraphSession createSession();

    @Test
    void bootstrapKernelIsSelfDescribingAndInspectableInGraph() {
        GraphSession graph = createSession();

        assertThat(graph.findEntity(BootstrapIds.META_OBJECT_TYPE)).isPresent();
        assertThat(graph.findEntity(BootstrapIds.META_VALUE_TYPE)).isPresent();
        assertThat(graph.findEntity(BootstrapIds.META_PREDICATE_TYPE)).isPresent();
        assertThat(graph.findEntity(BootstrapIds.ANY_EDGE)).isPresent();

        assertThat(graph.resolveName(BootstrapIds.META_OBJECT_TYPE)).contains("veg.meta.ObjectType");
        assertThat(graph.resolveName(BootstrapIds.HAS_DOMAIN)).contains("veg.meta.hasDomain");
        assertThat(graph.findOutgoingEdges(BootstrapIds.SYSTEM_IDENTIFIER_VALUE_TYPE, BootstrapIds.HAS_SCALAR_TYPE))
                .singleElement()
                .extracting(Edge::targetId)
                .isEqualTo(BootstrapIds.SCALAR_STRING);
        assertThat(graph.findOutgoingEdges(BootstrapIds.HAS_DOMAIN, BootstrapIds.HAS_DOMAIN))
                .singleElement()
                .extracting(Edge::targetId)
                .isEqualTo(BootstrapIds.META_PREDICATE_TYPE);
    }

    @Test
    void t01ValidTypedRelationPersonHasFirstNameAndSchemaInGraph() {
        GraphSession graph = createSession();

        EntityId personType = graph.defineObjectType("Person");
        EntityId firstNameType = graph.defineValueType("FirstName", ValueType.STRING);
        EntityId hasFirstName = graph.definePredicate("hasFirstName", personType, firstNameType);

        // Schema definitions themselves exist as graph entities and edges (M-05)
        assertThat(personType.isBootstrap()).isFalse();
        assertThat(graph.findTypeByName("Person")).contains(personType);
        assertThat(graph.findTypeByName("FirstName")).contains(firstNameType);
        assertThat(graph.findPredicateByName("hasFirstName")).contains(hasFirstName);
        assertThat(graph.resolveName(personType)).contains("Person");
        assertThat(graph.findOutgoingEdges(firstNameType, BootstrapIds.HAS_SCALAR_TYPE))
                .singleElement()
                .extracting(Edge::targetId)
                .isEqualTo(BootstrapIds.SCALAR_STRING);
        assertThat(graph.findOutgoingEdges(hasFirstName, BootstrapIds.HAS_DOMAIN))
                .singleElement()
                .extracting(Edge::targetId)
                .isEqualTo(personType);
        assertThat(graph.findOutgoingEdges(hasFirstName, BootstrapIds.HAS_RANGE))
                .singleElement()
                .extracting(Edge::targetId)
                .isEqualTo(firstNameType);

        // Instantiate Person and FirstName("Thomas") and connect via hasFirstName
        EntityId person = graph.createObject(personType);
        EntityId thomas = graph.createValue(firstNameType, Value.of("Thomas"));
        EntityId edgeId = graph.connect(person, hasFirstName, thomas);

        EntityNode edgeNode = graph.findEntity(edgeId).orElseThrow();
        assertThat(edgeNode.kind()).isEqualTo(EntityKind.EDGE);
        assertThat(edgeNode.typeId()).isEqualTo(BootstrapIds.META_EDGE_TYPE);

        List<Edge> outgoing = graph.findOutgoingEdges(person, hasFirstName);
        assertThat(outgoing).containsExactly(new Edge(edgeId, person, hasFirstName, thomas));
        assertThat(graph.findEntity(thomas).orElseThrow().payload()).contains(Value.of("Thomas"));
    }

    @Test
    void t02InvalidValueTypeAndDomainRangeViolationsAreRejectedAtomically() {
        GraphSession graph = createSession();

        EntityId personType = graph.defineObjectType("Person");
        EntityId docType = graph.defineObjectType("SourceDocument");
        EntityId firstNameType = graph.defineValueType("FirstName", ValueType.STRING);
        EntityId hasFirstName = graph.definePredicate("hasFirstName", personType, firstNameType);

        EntityId person = graph.createObject(personType);
        EntityId doc = graph.createObject(docType);
        EntityId thomas = graph.createValue(firstNameType, Value.of("Thomas"));

        long entitiesBefore = graph.entityCount();
        long edgesBefore = graph.edgeCount();

        // Scalar type mismatch: Integer(42) into STRING FirstName
        assertThatThrownBy(() -> graph.createValue(firstNameType, Value.of(42L)))
                .isInstanceOf(TypeMismatchException.class);
        assertThat(graph.entityCount()).isEqualTo(entitiesBefore);
        assertThat(graph.edgeCount()).isEqualTo(edgesBefore);

        // Range violation: SourceDocument instead of FirstName
        assertThatThrownBy(() -> graph.connect(person, hasFirstName, doc))
                .isInstanceOf(DomainRangeViolationException.class);
        assertThat(graph.entityCount()).isEqualTo(entitiesBefore);
        assertThat(graph.edgeCount()).isEqualTo(edgesBefore);

        // Domain violation: SourceDocument instead of Person
        assertThatThrownBy(() -> graph.connect(doc, hasFirstName, thomas))
                .isInstanceOf(DomainRangeViolationException.class);
        assertThat(graph.entityCount()).isEqualTo(entitiesBefore);
        assertThat(graph.edgeCount()).isEqualTo(edgesBefore);

        // Meta-type mismatch: createObject with valueTypeId, or connect with non-predicate
        assertThatThrownBy(() -> graph.createObject(firstNameType))
                .isInstanceOf(TypeMismatchException.class);
        assertThatThrownBy(() -> graph.connect(person, personType, thomas))
                .isInstanceOf(TypeMismatchException.class);
        assertThat(graph.entityCount()).isEqualTo(entitiesBefore);
        assertThat(graph.edgeCount()).isEqualTo(edgesBefore);
    }

    @Test
    void t03ExplicitAttributeSharingAllowsSeparateRelationLifecycles() {
        GraphSession graph = createSession();

        EntityId personType = graph.defineObjectType("Person");
        EntityId firstNameType = graph.defineValueType("FirstName", ValueType.STRING);
        EntityId hasFirstName = graph.definePredicate("hasFirstName", personType, firstNameType);

        EntityId person1 = graph.createObject(personType);
        EntityId person2 = graph.createObject(personType);
        EntityId sharedThomas = graph.createValue(firstNameType, Value.of("Thomas"));

        EntityId edge1 = graph.connect(person1, hasFirstName, sharedThomas);
        EntityId edge2 = graph.connect(person2, hasFirstName, sharedThomas);

        assertThat(edge1).isNotEqualTo(edge2);
        assertThat(graph.findIncomingEdges(sharedThomas, hasFirstName))
                .extracting(Edge::sourceId)
                .containsExactlyInAnyOrder(person1, person2);

        // Removing person1's relation leaves person2's relation to the shared attribute intact
        graph.disconnect(edge1);

        assertThat(graph.findEdge(edge1)).isEmpty();
        assertThat(graph.findOutgoingEdges(person1, hasFirstName)).isEmpty();
        assertThat(graph.findOutgoingEdges(person2, hasFirstName))
                .containsExactly(new Edge(edge2, person2, hasFirstName, sharedThomas));
        assertThat(graph.findEntity(sharedThomas)).isPresent();
    }

    @Test
    void t04RelationCanBeAnnotatedByOtherRelationsAndProtectedByReferentialIntegrity() {
        GraphSession graph = createSession();

        EntityId personType = graph.defineObjectType("Person");
        EntityId docType = graph.defineObjectType("SourceDocument");
        EntityId firstNameType = graph.defineValueType("FirstName", ValueType.STRING);
        EntityId confidenceType = graph.defineValueType("Confidence", ValueType.DECIMAL);

        EntityId hasFirstName = graph.definePredicate("hasFirstName", personType, firstNameType);
        EntityId supportedBy = graph.definePredicate("supportedBy", BootstrapIds.ANY_EDGE, docType);
        EntityId hasConfidence = graph.definePredicate("hasConfidence", BootstrapIds.ANY_EDGE, confidenceType);

        EntityId person = graph.createObject(personType);
        EntityId thomas = graph.createValue(firstNameType, Value.of("Thomas"));
        EntityId sourceDoc = graph.createObject(docType);
        EntityId confidenceVal = graph.createValue(confidenceType, Value.of(new BigDecimal("0.95")));

        EntityId statementEdge = graph.connect(person, hasFirstName, thomas);
        EntityId provenanceEdge = graph.connect(statementEdge, supportedBy, sourceDoc);
        EntityId confidenceEdge = graph.connect(statementEdge, hasConfidence, confidenceVal);

        // Traversable from the relation entity itself
        assertThat(graph.findOutgoingEdges(statementEdge, supportedBy))
                .containsExactly(new Edge(provenanceEdge, statementEdge, supportedBy, sourceDoc));
        assertThat(graph.findOutgoingEdges(statementEdge, hasConfidence))
                .containsExactly(new Edge(confidenceEdge, statementEdge, hasConfidence, confidenceVal));
        assertThat(graph.findIncomingEdges(sourceDoc, supportedBy))
                .singleElement()
                .extracting(Edge::sourceId)
                .isEqualTo(statementEdge);

        // Referential integrity: statementEdge cannot be disconnected while provenance/confidence edges point from it
        assertThatThrownBy(() -> graph.disconnect(statementEdge))
                .isInstanceOf(ReferentialIntegrityException.class);

        // Once dependent edges are removed, statementEdge can be disconnected cleanly
        graph.disconnect(provenanceEdge);
        graph.disconnect(confidenceEdge);
        graph.disconnect(statementEdge);
        assertThat(graph.findEdge(statementEdge)).isEmpty();
        assertThat(graph.findEntity(statementEdge)).isEmpty();
    }

    @Test
    void schemaValidationRejectsDuplicatesBlanksMissingEntitiesAndBootstrapMutations() {
        GraphSession graph = createSession();

        EntityId personType = graph.defineObjectType("Person");
        EntityId person = graph.createObject(personType);

        assertThatThrownBy(() -> graph.defineObjectType("Person"))
                .isInstanceOf(DuplicateSchemaElementException.class);
        assertThatThrownBy(() -> graph.defineValueType("Person", ValueType.STRING))
                .isInstanceOf(DuplicateSchemaElementException.class);
        assertThatThrownBy(() -> graph.defineObjectType("   "))
                .isInstanceOf(SchemaViolationException.class);
        assertThatThrownBy(() -> graph.definePredicate("badPred", person, personType))
                .isInstanceOf(TypeMismatchException.class);
        assertThatThrownBy(() -> graph.createObject(new EntityId(999_999L)))
                .isInstanceOf(EntityNotFoundException.class);

        Edge schemaNameEdge = graph.findOutgoingEdges(personType, BootstrapIds.HAS_NAME).getFirst();
        assertThatThrownBy(() -> graph.disconnect(schemaNameEdge.id()))
                .isInstanceOf(SchemaViolationException.class);
    }
}
