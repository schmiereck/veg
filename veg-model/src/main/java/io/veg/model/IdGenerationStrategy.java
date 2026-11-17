package io.veg.model;

/**
 * Strategy used by VEG storage engines to allocate new user-space {@link EntityId}s.
 */
public enum IdGenerationStrategy {
    /** Monotonically increasing 64-bit sequential IDs starting at {@link EntityId#USER_MIN_ID}. */
    SEQUENTIAL,
    /** Time-ordered 128-bit RFC 9562 UUIDv7 identifiers for distributed offline creation with trie locality. */
    UUID_V7,
    /** Cryptographically/randomly distributed 128-bit RFC 4122 UUIDv4 identifiers. */
    UUID_V4
}
