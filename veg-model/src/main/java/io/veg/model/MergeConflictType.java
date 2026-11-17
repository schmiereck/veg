package io.veg.model;

/**
 * Classification of structural and semantic conflicts detected during a three-way branch merge (H5, T11, T12).
 */
public enum MergeConflictType {
    /** Shared mutable value modified incompatibly or mutated in one branch while newly linked in the other (T11). */
    SHARED_VALUE_MODIFIED,
    /** Functional (maxOutgoing=1) predicate assigned conflicting targets for the same source entity across branches. */
    CONCURRENT_FUNCTIONAL_ATTRIBUTE,
    /** Edge disconnected in one branch while annotated or referenced as an endpoint in the other branch. */
    EDGE_DELETED_AND_ANNOTATED,
    /** Schema element renamed incompatibly in both branches, or two distinct elements assigned the same name. */
    CONCURRENT_SCHEMA_RENAME,
    /** Predicate constraints updated to incompatible values across both branches. */
    CONCURRENT_CONSTRAINT_CHANGE,
    /** Tightened schema constraint in one branch is violated by data introduced in the other branch (T12). */
    SCHEMA_CONSTRAINT_DATA_INCOMPATIBLE
}
