package io.veg.engine.memory;

import io.veg.model.Value;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Legal payload interner for immutable {@link Value} objects ({@code M-03}, {@code I-07}).
 *
 * <p>Canonicalizes duplicate value payloads on heap without altering or collapsing distinct
 * {@code EntityId} identities.</p>
 */
final class ValueInterner {

    private final ConcurrentHashMap<Value, Value> pool = new ConcurrentHashMap<>();

    Value intern(Value value) {
        if (value == null) {
            return null;
        }
        return pool.computeIfAbsent(value, v -> v);
    }

    int distinctValueCount() {
        return pool.size();
    }
}
