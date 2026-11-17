package io.veg.model;

/**
 * Structural classification of an entity node in the graph.
 */
public enum EntityKind {
    /** Hardcoded or user-defined schema/meta entity (object type, value type, predicate, wildcard). */
    META_TYPE,
    /** Primitive scalar value type entity in the bootstrap kernel. */
    SCALAR_TYPE,
    /** Domain object instance (e.g., an instance of {@code Person} or {@code SourceDocument}). */
    OBJECT,
    /** First-class typed attribute value entity carrying a {@link Value} payload. */
    VALUE,
    /** First-class edge entity that also exists in the edge store and can be referenced by other edges. */
    EDGE
}
