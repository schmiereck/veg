package io.veg.engine.memory;

import io.veg.model.EntityId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Immutable structurally-shared persistent trie map keyed by {@link EntityId}.
 *
 * <p>Uses a single-level {@link PersistentLongMap} with zero overhead for sequential/bootstrap IDs
 * ({@code msb == 0}) and a two-level radix trie ({@code msb -> lsb}) for 128-bit UUIDs.</p>
 */
final class PersistentEntityMap<V> {

    private static final PersistentEntityMap<?> EMPTY =
            new PersistentEntityMap<>(PersistentLongMap.empty(), PersistentLongMap.empty(), 0);

    private final PersistentLongMap<V> sequential;
    private final PersistentLongMap<PersistentLongMap<V>> uuids;
    private final int uuidSize;

    private PersistentEntityMap(
            PersistentLongMap<V> sequential,
            PersistentLongMap<PersistentLongMap<V>> uuids,
            int uuidSize
    ) {
        this.sequential = sequential;
        this.uuids = uuids;
        this.uuidSize = uuidSize;
    }

    @SuppressWarnings("unchecked")
    static <V> PersistentEntityMap<V> empty() {
        return (PersistentEntityMap<V>) EMPTY;
    }

    int size() {
        return sequential.size() + uuidSize;
    }

    V get(EntityId id) {
        if (!id.isUuid()) {
            return sequential.get(id.lsb());
        }
        PersistentLongMap<V> bucket = uuids.get(id.msb());
        return bucket == null ? null : bucket.get(id.lsb());
    }

    PersistentEntityMap<V> put(EntityId id, V value) {
        if (!id.isUuid()) {
            PersistentLongMap<V> nextSeq = sequential.put(id.lsb(), value);
            return nextSeq == sequential ? this : new PersistentEntityMap<>(nextSeq, uuids, uuidSize);
        }
        PersistentLongMap<V> bucket = uuids.get(id.msb());
        PersistentLongMap<V> targetBucket = bucket != null ? bucket : PersistentLongMap.empty();
        int prevBucketSize = targetBucket.size();
        PersistentLongMap<V> nextBucket = targetBucket.put(id.lsb(), value);
        int delta = nextBucket.size() - prevBucketSize;
        PersistentLongMap<PersistentLongMap<V>> nextUuids = uuids.put(id.msb(), nextBucket);
        return new PersistentEntityMap<>(sequential, nextUuids, uuidSize + delta);
    }

    PersistentEntityMap<V> remove(EntityId id) {
        if (!id.isUuid()) {
            PersistentLongMap<V> nextSeq = sequential.remove(id.lsb());
            return nextSeq == sequential ? this : new PersistentEntityMap<>(nextSeq, uuids, uuidSize);
        }
        PersistentLongMap<V> bucket = uuids.get(id.msb());
        if (bucket == null) {
            return this;
        }
        int prevBucketSize = bucket.size();
        PersistentLongMap<V> nextBucket = bucket.remove(id.lsb());
        if (nextBucket == bucket) {
            return this;
        }
        int delta = nextBucket.size() - prevBucketSize;
        PersistentLongMap<PersistentLongMap<V>> nextUuids = nextBucket.size() == 0
                ? uuids.remove(id.msb())
                : uuids.put(id.msb(), nextBucket);
        return new PersistentEntityMap<>(sequential, nextUuids, uuidSize + delta);
    }

    void forEach(Consumer<V> action) {
        sequential.forEach(action);
        if (uuidSize > 0) {
            uuids.forEach(bucket -> bucket.forEach(action));
        }
    }

    List<V> valuesSorted(Comparator<V> comparator) {
        if (uuidSize == 0) {
            return sequential.valuesSorted(comparator);
        }
        List<V> list = new ArrayList<>(size());
        forEach(list::add);
        list.sort(comparator);
        return List.copyOf(list);
    }

    void collectUniqueTrieNodes(Set<Object> seenNodes) {
        sequential.collectUniqueTrieNodes(seenNodes);
        if (uuidSize > 0) {
            uuids.collectUniqueTrieNodes(seenNodes);
            uuids.forEach(bucket -> bucket.collectUniqueTrieNodes(seenNodes));
        }
    }
}
