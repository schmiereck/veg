package io.veg.model;

/**
 * Stable, branch- and version-independent identity of any persistent entity in VEG.
 *
 * <p>IDs in range {@code 1..999} are reserved for the minimal bootstrap kernel;
 * IDs starting at {@link #USER_MIN_ID} ({@code 1000}) identify schema and domain entities.</p>
 */
public record EntityId(long value) implements Comparable<EntityId> {

    public static final long BOOTSTRAP_MAX_ID = 999L;
    public static final long USER_MIN_ID = 1000L;

    public EntityId {
        if (value <= 0) {
            throw new IllegalArgumentException("EntityId value must be positive, was: " + value);
        }
    }

    /**
     * Returns {@code true} if this ID belongs to the hardcoded bootstrap kernel namespace.
     */
    public boolean isBootstrap() {
        return value <= BOOTSTRAP_MAX_ID;
    }

    @Override
    public int compareTo(EntityId other) {
        return Long.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "#" + value;
    }
}
