package io.veg.model;

/**
 * Controls which commit {@link DataCategory} classes are eligible during a branch merge (V-13).
 *
 * <p>Schema changes ({@link DataCategory#SCHEMA}) are always included in merges.</p>
 */
public record MergePolicy(
        boolean includeReferenceData,
        boolean includeOperationalData,
        boolean includeTestData
) {

    public static MergePolicy developmentDefault() {
        return new MergePolicy(true, false, false);
    }

    public static MergePolicy schemaOnly() {
        return new MergePolicy(false, false, false);
    }

    public static MergePolicy includeAll() {
        return new MergePolicy(true, true, true);
    }

    public boolean includes(DataCategory category) {
        return switch (category) {
            case SCHEMA -> true;
            case REFERENCE -> includeReferenceData;
            case OPERATIONAL -> includeOperationalData;
            case TEST -> includeTestData;
        };
    }
}
