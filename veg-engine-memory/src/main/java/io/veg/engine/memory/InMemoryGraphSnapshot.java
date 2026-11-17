package io.veg.engine.memory;

/**
 * Immutable read-only snapshot of a graph state at a specific commit (V-04).
 */
public final class InMemoryGraphSnapshot extends AbstractGraphReader {

    private final MutableGraphState frozenState;

    InMemoryGraphSnapshot(MutableGraphState frozenCopy) {
        this.frozenState = frozenCopy;
    }

    @Override
    protected MutableGraphState state() {
        return frozenState;
    }
}
