package io.veg.model;

import java.util.Map;
import java.util.Objects;

/**
 * Summary of importing an RDF Turtle or N-Triples dataset into a VEG {@code GraphSession}.
 */
public record RdfImportResult(
        Map<String, EntityId> resourceEntities,
        int importedObjectCount,
        int importedLiteralCount,
        int importedEdgeCount,
        int importedAnnotationCount
) {
    public RdfImportResult {
        resourceEntities = Map.copyOf(Objects.requireNonNull(resourceEntities, "resourceEntities"));
    }
}
