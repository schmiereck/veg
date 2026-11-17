package io.veg.model;

import java.util.List;
import java.util.Objects;

/**
 * Result of exporting a VEG graph snapshot to RDF, including explicit provenance warnings (T18).
 */
public record RdfExportResult(
        String content,
        int exportedTripleCount,
        int reifiedAnnotationCount,
        List<String> warnings
) {
    public RdfExportResult {
        Objects.requireNonNull(content, "content must not be null");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
