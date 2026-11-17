package io.veg.model;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stable, branch- and version-independent identity of any persistent entity in VEG.
 *
 * <p>Supports both compact 64-bit sequential/bootstrap identifiers ({@code msb == 0}, {@code lsb > 0})
 * and full 128-bit RFC UUIDs ({@code msb != 0}) for distributed creation and patch merging.</p>
 *
 * <p>IDs with {@code msb == 0} in range {@code 1..999} are reserved for the minimal bootstrap kernel;
 * user-space IDs are either sequential {@code >= 1000} or 128-bit UUIDs.</p>
 */
public record EntityId(long msb, long lsb) implements Comparable<EntityId> {

    public static final long BOOTSTRAP_MAX_ID = 999L;
    public static final long USER_MIN_ID = 1000L;

    private static final AtomicInteger V7_SUB_MS_SEQ = new AtomicInteger();

    public EntityId {
        if (msb == 0L && lsb <= 0L) {
            throw new IllegalArgumentException(
                    "Sequential/bootstrap EntityId lsb must be positive when msb == 0, was: " + lsb
            );
        }
    }

    /**
     * Compatibility constructor for compact 64-bit sequential or bootstrap IDs.
     */
    public EntityId(long value) {
        this(0L, value);
    }

    /**
     * Compatibility accessor returning the 64-bit value ({@code lsb}) for sequential/bootstrap IDs.
     */
    public long value() {
        return lsb;
    }

    public boolean isUuid() {
        return msb != 0L;
    }

    /**
     * Returns {@code true} if this ID belongs to the hardcoded bootstrap kernel namespace.
     */
    public boolean isBootstrap() {
        return msb == 0L && lsb <= BOOTSTRAP_MAX_ID;
    }

    public UUID toUuid() {
        return new UUID(msb, lsb);
    }

    public static EntityId fromUuid(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        return new EntityId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    public static EntityId randomUuid() {
        return fromUuid(UUID.randomUUID());
    }

    /**
     * Generates a time-ordered 128-bit RFC 9562 UUIDv7 identifier ({@code msb != 0}).
     */
    public static EntityId newUuidV7() {
        long millis = System.currentTimeMillis() & 0xFFFF_FFFF_FFFFL;
        int seq = V7_SUB_MS_SEQ.getAndIncrement() & 0x0FFF;
        long msb = (millis << 16) | 0x7000L | seq;
        long rand = ThreadLocalRandom.current().nextLong();
        long lsb = (rand & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new EntityId(msb, lsb);
    }

    /**
     * Parses either a numeric/hashed sequential ID ({@code #1025}, {@code 1025})
     * or a hyphenated UUID string ({@code #01933b4a-...}, {@code 01933b4a-...}).
     */
    public static EntityId parse(String text) {
        Objects.requireNonNull(text, "entityId text must not be null");
        String normalized = text.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Blank EntityId string");
        }
        if (normalized.indexOf('-') >= 0) {
            return fromUuid(UUID.fromString(normalized));
        }
        return new EntityId(0L, Long.parseLong(normalized));
    }

    public String compactString() {
        return msb == 0L ? Long.toString(lsb) : new UUID(msb, lsb).toString();
    }

    @Override
    public int compareTo(EntityId other) {
        if (this.isUuid() != other.isUuid()) {
            return this.isUuid() ? 1 : -1;
        }
        int cmp = Long.compareUnsigned(this.msb, other.msb);
        return cmp != 0 ? cmp : Long.compareUnsigned(this.lsb, other.lsb);
    }

    @Override
    public String toString() {
        return "#" + compactString();
    }
}
