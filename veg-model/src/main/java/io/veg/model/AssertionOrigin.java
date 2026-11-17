package io.veg.model;

/**
 * Distinguishes stored explicit edge assertions from dynamically inferred semantic assertions.
 */
public enum AssertionOrigin {
    EXPLICIT,
    DERIVED_INVERSE,
    DERIVED_SUPER_PREDICATE
}
