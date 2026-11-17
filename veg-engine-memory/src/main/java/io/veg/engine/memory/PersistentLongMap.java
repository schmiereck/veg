package io.veg.engine.memory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Immutable, structurally shared 32-way bit-partitioned radix trie keyed by 64-bit {@code long} IDs
 * for Engine C ({@link PersistentTreeVersionedDatabase}).
 *
 * <p>Single-entry subtrees are compressed as {@link Leaf} nodes and branch nodes allocate arrays sized
 * strictly to {@code Integer.bitCount(bitmap)}. Mutations copy only the {@code O(log32 N)} path from root
 * to the modified node while leaving all sibling subtrees physically shared across commits and branches.</p>
 */
final class PersistentLongMap<V> {

    private static final int BITS_PER_LEVEL = 5;
    private static final int LEVEL_MASK = 0x1F;
    private static final PersistentLongMap<?> EMPTY = new PersistentLongMap<>(null, 0);

    private sealed interface Node permits Branch, Leaf {
    }

    private record Leaf<V>(long key, V value) implements Node {
    }

    private record Branch(int bitmap, Object[] children) implements Node {
    }

    private final Node root;
    private final int size;

    private PersistentLongMap(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    @SuppressWarnings("unchecked")
    static <V> PersistentLongMap<V> empty() {
        return (PersistentLongMap<V>) EMPTY;
    }

    int size() {
        return size;
    }

    boolean containsKey(long key) {
        return get(key) != null;
    }

    @SuppressWarnings("unchecked")
    V get(long key) {
        Node cursor = root;
        int shift = 0;
        while (cursor != null) {
            if (cursor instanceof Leaf<?> leaf) {
                return leaf.key() == key ? (V) leaf.value() : null;
            }
            Branch branch = (Branch) cursor;
            int slot = (int) ((key >>> shift) & LEVEL_MASK);
            int bit = 1 << slot;
            if ((branch.bitmap() & bit) == 0) {
                return null;
            }
            int idx = Integer.bitCount(branch.bitmap() & (bit - 1));
            cursor = (Node) branch.children()[idx];
            shift += BITS_PER_LEVEL;
        }
        return null;
    }

    PersistentLongMap<V> put(long key, V value) {
        Objects.requireNonNull(value, "value must not be null");
        boolean[] added = new boolean[1];
        Node nextRoot = putNode(root, 0, key, value, added);
        if (nextRoot == root) {
            return this;
        }
        return new PersistentLongMap<>(nextRoot, added[0] ? size + 1 : size);
    }

    PersistentLongMap<V> remove(long key) {
        if (root == null) {
            return this;
        }
        boolean[] removed = new boolean[1];
        Node nextRoot = removeNode(root, 0, key, removed);
        if (!removed[0]) {
            return this;
        }
        if (nextRoot == null) {
            return empty();
        }
        return new PersistentLongMap<>(nextRoot, size - 1);
    }

    void forEach(Consumer<V> action) {
        traverse(root, action);
    }

    List<V> valuesSorted(Comparator<? super V> comparator) {
        List<V> all = new ArrayList<>(size);
        traverse(root, all::add);
        all.sort(comparator);
        return List.copyOf(all);
    }

    void collectUniqueTrieNodes(Set<Object> seenNodes) {
        collectNodes(root, seenNodes);
    }

    @SuppressWarnings("unchecked")
    private Node putNode(Node node, int shift, long key, V value, boolean[] added) {
        if (node == null) {
            added[0] = true;
            return new Leaf<>(key, value);
        }
        if (node instanceof Leaf<?> rawLeaf) {
            Leaf<V> existing = (Leaf<V>) rawLeaf;
            if (existing.key() == key) {
                if (Objects.equals(existing.value(), value)) {
                    return node;
                }
                return new Leaf<>(key, value);
            }
            added[0] = true;
            return mergeTwoLeaves(shift, existing, new Leaf<>(key, value));
        }

        Branch branch = (Branch) node;
        int slot = (int) ((key >>> shift) & LEVEL_MASK);
        int bit = 1 << slot;
        int idx = Integer.bitCount(branch.bitmap() & (bit - 1));

        if ((branch.bitmap() & bit) == 0) {
            added[0] = true;
            Object[] src = branch.children();
            Object[] dst = new Object[src.length + 1];
            System.arraycopy(src, 0, dst, 0, idx);
            dst[idx] = new Leaf<>(key, value);
            System.arraycopy(src, idx, dst, idx + 1, src.length - idx);
            return new Branch(branch.bitmap() | bit, dst);
        }

        Node oldChild = (Node) branch.children()[idx];
        Node newChild = putNode(oldChild, shift + BITS_PER_LEVEL, key, value, added);
        if (newChild == oldChild) {
            return node;
        }
        Object[] cloned = Arrays.copyOf(branch.children(), branch.children().length);
        cloned[idx] = newChild;
        return new Branch(branch.bitmap(), cloned);
    }

    private Node mergeTwoLeaves(int shift, Leaf<V> a, Leaf<V> b) {
        int slotA = (int) ((a.key() >>> shift) & LEVEL_MASK);
        int slotB = (int) ((b.key() >>> shift) & LEVEL_MASK);
        if (slotA == slotB) {
            Node sub = mergeTwoLeaves(shift + BITS_PER_LEVEL, a, b);
            return new Branch(1 << slotA, new Object[]{sub});
        }
        int bitmap = (1 << slotA) | (1 << slotB);
        Object[] children = slotA < slotB ? new Object[]{a, b} : new Object[]{b, a};
        return new Branch(bitmap, children);
    }

    private Node removeNode(Node node, int shift, long key, boolean[] removed) {
        if (node == null) {
            return null;
        }
        if (node instanceof Leaf<?> leaf) {
            if (leaf.key() == key) {
                removed[0] = true;
                return null;
            }
            return node;
        }

        Branch branch = (Branch) node;
        int slot = (int) ((key >>> shift) & LEVEL_MASK);
        int bit = 1 << slot;
        if ((branch.bitmap() & bit) == 0) {
            return node;
        }
        int idx = Integer.bitCount(branch.bitmap() & (bit - 1));
        Node oldChild = (Node) branch.children()[idx];
        Node newChild = removeNode(oldChild, shift + BITS_PER_LEVEL, key, removed);
        if (!removed[0] || newChild == oldChild) {
            return node;
        }

        Object[] src = branch.children();
        if (newChild == null) {
            if (src.length == 1) {
                return null;
            }
            if (src.length == 2) {
                Node survivor = (Node) src[idx == 0 ? 1 : 0];
                if (survivor instanceof Leaf<?>) {
                    return survivor;
                }
            }
            Object[] dst = new Object[src.length - 1];
            System.arraycopy(src, 0, dst, 0, idx);
            System.arraycopy(src, idx + 1, dst, idx, src.length - idx - 1);
            return new Branch(branch.bitmap() & ~bit, dst);
        }

        if (src.length == 1 && newChild instanceof Leaf<?>) {
            return newChild;
        }
        Object[] cloned = Arrays.copyOf(src, src.length);
        cloned[idx] = newChild;
        return new Branch(branch.bitmap(), cloned);
    }

    @SuppressWarnings("unchecked")
    private void traverse(Node node, Consumer<V> action) {
        if (node == null) {
            return;
        }
        if (node instanceof Leaf<?> leaf) {
            action.accept((V) leaf.value());
            return;
        }
        Branch branch = (Branch) node;
        for (Object child : branch.children()) {
            traverse((Node) child, action);
        }
    }

    private static void collectNodes(Node node, Set<Object> seenNodes) {
        if (node == null || !seenNodes.add(node)) {
            return;
        }
        if (node instanceof Branch branch) {
            for (Object child : branch.children()) {
                collectNodes((Node) child, seenNodes);
            }
        }
    }
}
