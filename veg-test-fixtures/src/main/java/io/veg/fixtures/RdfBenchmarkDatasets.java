package io.veg.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads bundled standard RDF Turtle benchmark datasets (LUBM & BSBM subsets) from classpath resources.
 */
public final class RdfBenchmarkDatasets {

    private RdfBenchmarkDatasets() {
    }

    public static String lubmUniversityTurtle() {
        return loadResource("/rdf/lubm-university.ttl");
    }

    public static String bsbmEcommerceTurtle() {
        return loadResource("/rdf/bsbm-ecommerce.ttl");
    }

    private static String loadResource(String path) {
        try (InputStream in = RdfBenchmarkDatasets.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled RDF dataset resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load RDF dataset: " + path, ex);
        }
    }
}
