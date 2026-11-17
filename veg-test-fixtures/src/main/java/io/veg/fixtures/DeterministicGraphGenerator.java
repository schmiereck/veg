package io.veg.fixtures;

import io.veg.engine.GraphSession;
import io.veg.model.BootstrapIds;
import io.veg.model.EntityId;
import io.veg.model.Value;
import io.veg.model.ValueType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Deterministic generator for reproducible Stage 0/1 benchmarks and integration datasets.
 */
public final class DeterministicGraphGenerator {

    private static final String[] FIRST_NAMES = {
            "Thomas", "Anna", "Lukas", "Mia", "Jonas", "Lea", "Paul", "Clara",
            "Felix", "Laura", "David", "Hannah", "Leon", "Sofia", "Julian", "Marie"
    };

    private static final String[] LAST_NAMES = {
            "Mueller", "Schmidt", "Schneider", "Fischer", "Weber", "Meyer", "Wagner", "Becker",
            "Schulz", "Hoffmann", "Koch", "Bauer", "Richter", "Klein", "Wolf", "Neumann"
    };

    public record SchemaIds(
            EntityId personType,
            EntityId sourceDocumentType,
            EntityId firstNameType,
            EntityId lastNameType,
            EntityId ageType,
            EntityId confidenceType,
            EntityId documentTitleType,
            EntityId hasFirstName,
            EntityId hasLastName,
            EntityId hasAge,
            EntityId hasTitle,
            EntityId supportedBy,
            EntityId hasConfidence
    ) {
    }

    public record GeneratedDataset(
            SchemaIds schema,
            List<EntityId> personIds,
            List<EntityId> documentIds,
            List<EntityId> annotatedEdgeIds,
            long domainOperationsCount
    ) {
    }

    public GeneratedDataset generate(
            GraphSession session,
            long seed,
            int personCount,
            double sharedValueRatio,
            double provenanceRatio
    ) {
        Objects.requireNonNull(session, "session must not be null");
        if (personCount < 0) {
            throw new IllegalArgumentException("personCount must be >= 0");
        }
        if (sharedValueRatio < 0.0 || sharedValueRatio > 1.0) {
            throw new IllegalArgumentException("sharedValueRatio must be in [0.0, 1.0]");
        }
        if (provenanceRatio < 0.0 || provenanceRatio > 1.0) {
            throw new IllegalArgumentException("provenanceRatio must be in [0.0, 1.0]");
        }

        long ops = 0;
        SchemaIds schema = defineSchema(session);
        ops += 13;

        SplittableRandom random = new SplittableRandom(seed);

        int docCount = Math.max(1, personCount / 20);
        List<EntityId> documents = new ArrayList<>(docCount);
        for (int i = 0; i < docCount; i++) {
            EntityId doc = session.createObject(schema.sourceDocumentType());
            EntityId titleVal = session.createValue(schema.documentTitleType(), Value.of("Registry-Batch-" + i));
            session.connect(doc, schema.hasTitle(), titleVal);
            documents.add(doc);
            ops += 3;
        }

        List<EntityId> sharedFirstNames = new ArrayList<>(FIRST_NAMES.length);
        for (String name : FIRST_NAMES) {
            sharedFirstNames.add(session.createValue(schema.firstNameType(), Value.of(name)));
            ops++;
        }

        List<EntityId> sharedLastNames = new ArrayList<>(LAST_NAMES.length);
        for (String name : LAST_NAMES) {
            sharedLastNames.add(session.createValue(schema.lastNameType(), Value.of(name)));
            ops++;
        }

        List<EntityId> persons = new ArrayList<>(personCount);
        List<EntityId> annotatedEdges = new ArrayList<>();

        for (int i = 0; i < personCount; i++) {
            EntityId person = session.createObject(schema.personType());
            persons.add(person);
            ops++;

            EntityId firstNameVal;
            if (random.nextDouble() < sharedValueRatio) {
                firstNameVal = sharedFirstNames.get(random.nextInt(sharedFirstNames.size()));
            } else {
                String uniqueFirst = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)] + "-" + i;
                firstNameVal = session.createValue(schema.firstNameType(), Value.of(uniqueFirst));
                ops++;
            }
            EntityId firstNameEdge = session.connect(person, schema.hasFirstName(), firstNameVal);
            ops++;

            EntityId lastNameVal;
            if (random.nextDouble() < sharedValueRatio) {
                lastNameVal = sharedLastNames.get(random.nextInt(sharedLastNames.size()));
            } else {
                String uniqueLast = LAST_NAMES[random.nextInt(LAST_NAMES.length)] + "-" + i;
                lastNameVal = session.createValue(schema.lastNameType(), Value.of(uniqueLast));
                ops++;
            }
            session.connect(person, schema.hasLastName(), lastNameVal);
            ops++;

            EntityId ageVal = session.createValue(schema.ageType(), Value.of(18L + random.nextInt(70)));
            session.connect(person, schema.hasAge(), ageVal);
            ops += 2;

            if (random.nextDouble() < provenanceRatio) {
                EntityId doc = documents.get(random.nextInt(documents.size()));
                session.connect(firstNameEdge, schema.supportedBy(), doc);
                BigDecimal confidence = BigDecimal.valueOf(50 + random.nextInt(51), 2).setScale(2, RoundingMode.UNNECESSARY);
                EntityId confidenceVal = session.createValue(schema.confidenceType(), Value.of(confidence));
                session.connect(firstNameEdge, schema.hasConfidence(), confidenceVal);
                annotatedEdges.add(firstNameEdge);
                ops += 3;
            }
        }

        return new GeneratedDataset(schema, List.copyOf(persons), List.copyOf(documents), List.copyOf(annotatedEdges), ops);
    }

    private SchemaIds defineSchema(GraphSession session) {
        EntityId personType = session.defineObjectType("Person");
        EntityId sourceDocumentType = session.defineObjectType("SourceDocument");
        EntityId firstNameType = session.defineValueType("FirstName", ValueType.STRING);
        EntityId lastNameType = session.defineValueType("LastName", ValueType.STRING);
        EntityId ageType = session.defineValueType("Age", ValueType.INT);
        EntityId confidenceType = session.defineValueType("Confidence", ValueType.DECIMAL);
        EntityId documentTitleType = session.defineValueType("DocumentTitle", ValueType.STRING);

        EntityId hasFirstName = session.definePredicate("hasFirstName", personType, firstNameType);
        EntityId hasLastName = session.definePredicate("hasLastName", personType, lastNameType);
        EntityId hasAge = session.definePredicate("hasAge", personType, ageType);
        EntityId hasTitle = session.definePredicate("hasTitle", sourceDocumentType, documentTitleType);
        EntityId supportedBy = session.definePredicate("supportedBy", BootstrapIds.ANY_EDGE, sourceDocumentType);
        EntityId hasConfidence = session.definePredicate("hasConfidence", BootstrapIds.ANY_EDGE, confidenceType);

        return new SchemaIds(
                personType,
                sourceDocumentType,
                firstNameType,
                lastNameType,
                ageType,
                confidenceType,
                documentTitleType,
                hasFirstName,
                hasLastName,
                hasAge,
                hasTitle,
                supportedBy,
                hasConfidence
        );
    }
}
