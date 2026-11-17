package io.veg.model;

import java.util.Objects;

/**
 * Configuration options controlling RDF serialization in {@code RdfGraphExporter}.
 */
public record RdfExportOptions(
        String baseNamespace,
        RdfFormat format,
        boolean includeSchema,
        boolean includeDerivedAssertions,
        boolean includeReifiedProvenance
) {
    public RdfExportOptions {
        Objects.requireNonNull(baseNamespace, "baseNamespace must not be null");
        Objects.requireNonNull(format, "format must not be null");
        if (baseNamespace.isBlank()) {
            throw new IllegalArgumentException("baseNamespace must not be blank");
        }
    }

    public static RdfExportOptions turtleDefault() {
        return new RdfExportOptions("http://veg.io/data#", RdfFormat.TURTLE, true, false, true);
    }

    public static RdfExportOptions turtleWithDerived() {
        return new RdfExportOptions("http://veg.io/data#", RdfFormat.TURTLE, true, true, true);
    }

    public static RdfExportOptions nTriplesDefault() {
        return new RdfExportOptions("http://veg.io/data#", RdfFormat.N_TRIPLES, true, false, true);
    }
}
