package io.veg.model;

/**
 * Immutable identifier of a persisted graph commit in the linear commit log (or commit DAG in Stage 4).
 *
 * <p>{@link #BOOTSTRAP} ({@code c0}) identifies the initial bootstrap state; user commits start at {@code 1}.</p>
 */
public record CommitId(long value) implements Comparable<CommitId> {

    public static final CommitId BOOTSTRAP = new CommitId(0L);

    public CommitId {
        if (value < 0) {
            throw new IllegalArgumentException("CommitId value must be >= 0, was: " + value);
        }
    }

    public boolean isBootstrap() {
        return value == 0L;
    }

    public CommitId next() {
        return new CommitId(value + 1L);
    }

    @Override
    public int compareTo(CommitId other) {
        return Long.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "c" + value;
    }
}
