package io.veg.model;

/**
 * Classification of commit payload semantics for merge filtering (V-13).
 */
public enum DataCategory {
    /** Schema definitions, constraints, and renames. Always included in development merges. */
    SCHEMA,
    /** Reference, seed, or configuration data intended to travel across development/release branches. */
    REFERENCE,
    /** Automated or manual test fixtures excluded from default development merges. */
    TEST,
    /** Live operational/customer business changes excluded from feature-to-main development merges. */
    OPERATIONAL
}
