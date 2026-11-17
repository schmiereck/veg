package io.veg.engine.persistent;

import io.veg.engine.memory.PersistentGraphSnapshot;
import io.veg.model.Actor;
import io.veg.model.Branch;
import io.veg.model.Change;
import io.veg.model.Commit;
import io.veg.model.CommitId;
import io.veg.model.DataCategory;
import io.veg.model.Edge;
import io.veg.model.EntityId;
import io.veg.model.EntityKind;
import io.veg.model.EntityNode;
import io.veg.model.Reason;
import io.veg.model.RedactionReceipt;
import io.veg.model.Tag;
import io.veg.model.Value;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32C;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Deterministic binary serializer and {@code CRC32C} envelope codec for VEG commits, snapshots,
 * cold archives, and {@code refs.meta}.
 */
final class BinaryGraphCodec {

    static final int MAGIC_REFS = 0x56454752;       // "VEGR"
    static final int MAGIC_CHECKPOINT = 0x5645474B; // "VEGK"
    static final int MAGIC_ARCHIVE = 0x56454741;    // "VEGA"
    static final short FORMAT_VERSION = 1;

    private static final int ENVELOPE_HEADER_BYTES = 14; // magic(4) + version(2) + length(4) + crc32c(4)

    record RefsMetadata(
            String activeBranch,
            Map<String, Branch> branches,
            Map<String, Tag> tags,
            Map<String, Set<String>> branchReadRestrictions,
            Optional<CommitId> latestCheckpointId,
            Optional<CommitId> archivedUpToCommitId,
            long nextUserEntityId,
            Map<String, CommitId> idempotentCommits,
            List<RedactionReceipt> redactionReceipts
    ) {
    }

    record DecodedSnapshot(CommitId commitId, PersistentGraphSnapshot snapshot) {
    }

    private BinaryGraphCodec() {
    }

    static int crc32c(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    static byte[] wrapEnvelope(int magic, byte[] payload) {
        int crc = crc32c(payload, 0, payload.length);
        ByteBuffer buf = ByteBuffer.allocate(ENVELOPE_HEADER_BYTES + payload.length);
        buf.putInt(magic);
        buf.putShort(FORMAT_VERSION);
        buf.putInt(payload.length);
        buf.putInt(crc);
        buf.put(payload);
        return buf.array();
    }

    static byte[] unwrapEnvelope(byte[] raw, int expectedMagic, String artifactLabel) {
        if (raw.length < ENVELOPE_HEADER_BYTES) {
            throw new StorageCorruptionException(
                    "Truncated " + artifactLabel + ": expected at least " + ENVELOPE_HEADER_BYTES + " bytes, got " + raw.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int magic = buf.getInt();
        if (magic != expectedMagic) {
            throw new StorageCorruptionException(
                    "Invalid magic header in " + artifactLabel + ": 0x" + Integer.toHexString(magic));
        }
        short version = buf.getShort();
        if (version != FORMAT_VERSION) {
            throw new StorageCorruptionException("Unsupported format version in " + artifactLabel + ": " + version);
        }
        int payloadLen = buf.getInt();
        int expectedCrc = buf.getInt();
        if (payloadLen < 0 || ENVELOPE_HEADER_BYTES + (long) payloadLen != raw.length) {
            throw new StorageCorruptionException(
                    "Length mismatch in " + artifactLabel + ": declared " + payloadLen + ", file bytes " + raw.length);
        }
        int actualCrc = crc32c(raw, ENVELOPE_HEADER_BYTES, payloadLen);
        if (actualCrc != expectedCrc) {
            throw new StorageCorruptionException(
                    "CRC32C mismatch in " + artifactLabel + ": expected 0x"
                            + Integer.toHexString(expectedCrc) + ", got 0x" + Integer.toHexString(actualCrc));
        }
        byte[] payload = new byte[payloadLen];
        buf.get(payload);
        return payload;
    }

    static byte[] compressEnvelope(int magic, byte[] uncompressedPayload, String artifactLabel) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                gzip.write(uncompressedPayload);
            }
            return wrapEnvelope(magic, baos.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to compress " + artifactLabel, ex);
        }
    }

    static byte[] decompressEnvelope(byte[] rawFileBytes, int expectedMagic, String artifactLabel) {
        byte[] gzBytes = unwrapEnvelope(rawFileBytes, expectedMagic, artifactLabel);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(gzBytes))) {
            return gzip.readAllBytes();
        } catch (IOException ex) {
            throw new StorageCorruptionException("Corrupted compressed stream in " + artifactLabel, ex);
        }
    }

    static byte[] encodeCommit(Commit commit) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeCommit(out, commit);
            out.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize commit " + commit.id(), ex);
        }
    }

    static Commit decodeCommit(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            Commit commit = readCommit(in);
            if (in.available() != 0) {
                throw new StorageCorruptionException("Unexpected trailing bytes after WAL commit payload");
            }
            return commit;
        } catch (StorageCorruptionException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new StorageCorruptionException("Corrupted commit binary payload", ex);
        }
    }

    static byte[] encodeCommitBatch(List<Commit> commits) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(commits.size());
            for (Commit commit : commits) {
                writeCommit(out, commit);
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize archive commit batch", ex);
        }
    }

    static List<Commit> decodeCommitBatch(byte[] payload, String artifactLabel) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = in.readInt();
            if (count < 0) {
                throw new StorageCorruptionException("Negative commit count in " + artifactLabel);
            }
            List<Commit> commits = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                commits.add(readCommit(in));
            }
            if (in.available() != 0) {
                throw new StorageCorruptionException("Trailing bytes in " + artifactLabel);
            }
            return List.copyOf(commits);
        } catch (StorageCorruptionException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new StorageCorruptionException("Corrupted archive batch in " + artifactLabel, ex);
        }
    }

    static byte[] encodeSnapshot(CommitId commitId, PersistentGraphSnapshot snapshot) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeLong(commitId.value());

            List<EntityNode> nodes = snapshot.allEntities();
            out.writeInt(nodes.size());
            for (EntityNode node : nodes) {
                writeEntityNode(out, node);
            }

            List<Edge> edges = snapshot.allEdges();
            out.writeInt(edges.size());
            for (Edge edge : edges) {
                writeEdge(out, edge);
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize snapshot for " + commitId, ex);
        }
    }

    static DecodedSnapshot decodeSnapshot(byte[] payload, String artifactLabel) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            CommitId commitId = new CommitId(in.readLong());

            int nodeCount = in.readInt();
            if (nodeCount < 0) {
                throw new StorageCorruptionException("Negative node count in " + artifactLabel);
            }
            List<EntityNode> nodes = new ArrayList<>(nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                nodes.add(readEntityNode(in));
            }

            int edgeCount = in.readInt();
            if (edgeCount < 0) {
                throw new StorageCorruptionException("Negative edge count in " + artifactLabel);
            }
            List<Edge> edges = new ArrayList<>(edgeCount);
            for (int i = 0; i < edgeCount; i++) {
                edges.add(readEdge(in));
            }
            if (in.available() != 0) {
                throw new StorageCorruptionException("Unexpected trailing bytes in " + artifactLabel);
            }
            return new DecodedSnapshot(commitId, PersistentGraphSnapshot.fromMaterialized(nodes, edges));
        } catch (StorageCorruptionException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new StorageCorruptionException("Corrupted checkpoint payload in " + artifactLabel, ex);
        }
    }

    static byte[] encodeRefsMeta(RefsMetadata meta) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, meta.activeBranch());

            out.writeInt(meta.branches().size());
            for (Branch branch : meta.branches().values()) {
                writeString(out, branch.name());
                out.writeLong(branch.headCommitId().value());
            }

            out.writeInt(meta.tags().size());
            for (Tag tag : meta.tags().values()) {
                writeString(out, tag.name());
                out.writeLong(tag.commitId().value());
            }

            out.writeInt(meta.branchReadRestrictions().size());
            for (var entry : meta.branchReadRestrictions().entrySet()) {
                writeString(out, entry.getKey());
                out.writeInt(entry.getValue().size());
                for (String principalId : entry.getValue()) {
                    writeString(out, principalId);
                }
            }

            writeOptionalCommitId(out, meta.latestCheckpointId());
            writeOptionalCommitId(out, meta.archivedUpToCommitId());
            out.writeLong(meta.nextUserEntityId());

            out.writeInt(meta.idempotentCommits().size());
            for (var entry : meta.idempotentCommits().entrySet()) {
                writeString(out, entry.getKey());
                out.writeLong(entry.getValue().value());
            }

            out.writeInt(meta.redactionReceipts().size());
            for (RedactionReceipt r : meta.redactionReceipts()) {
                writeEntityId(out, r.redactedEntityId());
                out.writeLong(r.redactedAt().getEpochSecond());
                out.writeInt(r.redactedAt().getNano());
                writeString(out, r.actor().id());
                writeString(out, r.actor().displayName());
                writeString(out, r.reason().text());
                writeString(out, r.originalPayloadSha256());
                out.writeInt(r.affectedCommitCount());
            }
            out.flush();
            return wrapEnvelope(MAGIC_REFS, baos.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize refs metadata", ex);
        }
    }

    static RefsMetadata decodeRefsMeta(byte[] rawBytes) {
        byte[] payload = unwrapEnvelope(rawBytes, MAGIC_REFS, "refs.meta");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            String activeBranch = readString(in);

            int branchCount = in.readInt();
            if (branchCount < 0) {
                throw new StorageCorruptionException("Negative branch count in refs.meta");
            }
            Map<String, Branch> branches = new LinkedHashMap<>();
            for (int i = 0; i < branchCount; i++) {
                String name = readString(in);
                CommitId head = new CommitId(in.readLong());
                branches.put(name, new Branch(name, head));
            }

            int tagCount = in.readInt();
            if (tagCount < 0) {
                throw new StorageCorruptionException("Negative tag count in refs.meta");
            }
            Map<String, Tag> tags = new LinkedHashMap<>();
            for (int i = 0; i < tagCount; i++) {
                String name = readString(in);
                CommitId target = new CommitId(in.readLong());
                tags.put(name, new Tag(name, target));
            }

            int restrCount = in.readInt();
            if (restrCount < 0) {
                throw new StorageCorruptionException("Negative restriction count in refs.meta");
            }
            Map<String, Set<String>> restrictions = new LinkedHashMap<>();
            for (int i = 0; i < restrCount; i++) {
                String branchName = readString(in);
                int pCount = in.readInt();
                if (pCount < 0) {
                    throw new StorageCorruptionException("Negative principal count in refs.meta");
                }
                Set<String> principals = new LinkedHashSet<>();
                for (int j = 0; j < pCount; j++) {
                    principals.add(readString(in));
                }
                restrictions.put(branchName, Set.copyOf(principals));
            }

            Optional<CommitId> latestCkpt = readOptionalCommitId(in);
            Optional<CommitId> archivedUpTo = readOptionalCommitId(in);
            long nextEntityId = in.readLong();

            int idemCount = in.readInt();
            if (idemCount < 0) {
                throw new StorageCorruptionException("Negative idempotent key count in refs.meta");
            }
            Map<String, CommitId> idempotentCommits = new LinkedHashMap<>(idemCount);
            for (int i = 0; i < idemCount; i++) {
                idempotentCommits.put(readString(in), new CommitId(in.readLong()));
            }

            int receiptCount = in.readInt();
            if (receiptCount < 0) {
                throw new StorageCorruptionException("Negative redaction receipt count in refs.meta");
            }
            List<RedactionReceipt> receipts = new ArrayList<>(receiptCount);
            for (int i = 0; i < receiptCount; i++) {
                EntityId entId = readEntityId(in);
                Instant ts = Instant.ofEpochSecond(in.readLong(), in.readInt());
                Actor actor = new Actor(readString(in), readString(in));
                Reason reason = Reason.of(readString(in));
                String sha = readString(in);
                int count = in.readInt();
                receipts.add(new RedactionReceipt(entId, ts, actor, reason, sha, count));
            }

            if (in.available() != 0) {
                throw new StorageCorruptionException("Trailing bytes in refs.meta");
            }
            return new RefsMetadata(
                    activeBranch, branches, tags, restrictions, latestCkpt, archivedUpTo, nextEntityId,
                    Map.copyOf(idempotentCommits), List.copyOf(receipts));
        } catch (StorageCorruptionException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new StorageCorruptionException("Corrupted refs.meta payload", ex);
        }
    }

    private static void writeCommit(DataOutputStream out, Commit commit) throws IOException {
        out.writeLong(commit.id().value());
        out.writeInt(commit.parentIds().size());
        for (CommitId parent : commit.parentIds()) {
            out.writeLong(parent.value());
        }
        writeString(out, commit.branchName());
        out.writeByte(commit.dataCategory().ordinal());
        writeString(out, commit.actor().id());
        writeString(out, commit.actor().displayName());
        writeString(out, commit.reason().text());
        out.writeBoolean(commit.correlationId().isPresent());
        if (commit.correlationId().isPresent()) {
            writeString(out, commit.correlationId().get());
        }
        out.writeLong(commit.timestamp().getEpochSecond());
        out.writeInt(commit.timestamp().getNano());
        out.writeInt(commit.changes().size());
        for (Change change : commit.changes()) {
            writeChange(out, change);
        }
    }

    private static Commit readCommit(DataInputStream in) throws IOException {
        CommitId id = new CommitId(in.readLong());
        int parentCount = in.readInt();
        if (parentCount < 0 || parentCount > 64) {
            throw new StorageCorruptionException("Invalid parent count in commit " + id + ": " + parentCount);
        }
        List<CommitId> parentIds = new ArrayList<>(parentCount);
        for (int i = 0; i < parentCount; i++) {
            parentIds.add(new CommitId(in.readLong()));
        }
        String branchName = readString(in);
        DataCategory category = DataCategory.values()[Byte.toUnsignedInt(in.readByte())];
        Actor actor = new Actor(readString(in), readString(in));
        Reason reason = Reason.of(readString(in));
        Optional<String> correlationId = in.readBoolean() ? Optional.of(readString(in)) : Optional.empty();
        Instant timestamp = Instant.ofEpochSecond(in.readLong(), in.readInt());
        int changeCount = in.readInt();
        if (changeCount < 0) {
            throw new StorageCorruptionException("Negative change count in commit " + id);
        }
        List<Change> changes = new ArrayList<>(changeCount);
        for (int i = 0; i < changeCount; i++) {
            changes.add(readChange(in));
        }
        return new Commit(id, parentIds, branchName, category, actor, reason, correlationId, timestamp, changes);
    }

    private static void writeChange(DataOutputStream out, Change change) throws IOException {
        switch (change) {
            case Change.EntityCreated(EntityNode node) -> {
                out.writeByte(1);
                writeEntityNode(out, node);
            }
            case Change.EntityRemoved(EntityNode node) -> {
                out.writeByte(2);
                writeEntityNode(out, node);
            }
            case Change.ValueUpdated(EntityId entityId, Value prev, Value next) -> {
                out.writeByte(3);
                writeEntityId(out, entityId);
                writeValue(out, prev);
                writeValue(out, next);
            }
            case Change.EdgeConnected(Edge edge) -> {
                out.writeByte(4);
                writeEdge(out, edge);
            }
            case Change.EdgeDisconnected(Edge edge) -> {
                out.writeByte(5);
                writeEdge(out, edge);
            }
        }
    }

    private static Change readChange(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case 1 -> new Change.EntityCreated(readEntityNode(in));
            case 2 -> new Change.EntityRemoved(readEntityNode(in));
            case 3 -> new Change.ValueUpdated(readEntityId(in), readValue(in), readValue(in));
            case 4 -> new Change.EdgeConnected(readEdge(in));
            case 5 -> new Change.EdgeDisconnected(readEdge(in));
            default -> throw new StorageCorruptionException("Unknown change tag: " + tag);
        };
    }

    private static void writeEntityNode(DataOutputStream out, EntityNode node) throws IOException {
        writeEntityId(out, node.id());
        writeEntityId(out, node.typeId());
        out.writeByte(node.kind().ordinal());
        if (node.kind() == EntityKind.VALUE) {
            writeValue(out, node.payload().orElseThrow());
        }
    }

    private static EntityNode readEntityNode(DataInputStream in) throws IOException {
        EntityId id = readEntityId(in);
        EntityId typeId = readEntityId(in);
        EntityKind kind = EntityKind.values()[Byte.toUnsignedInt(in.readByte())];
        Optional<Value> payload = kind == EntityKind.VALUE ? Optional.of(readValue(in)) : Optional.empty();
        return new EntityNode(id, typeId, kind, payload);
    }

    private static void writeEdge(DataOutputStream out, Edge edge) throws IOException {
        writeEntityId(out, edge.id());
        writeEntityId(out, edge.sourceId());
        writeEntityId(out, edge.predicateId());
        writeEntityId(out, edge.targetId());
    }

    private static Edge readEdge(DataInputStream in) throws IOException {
        return new Edge(
                readEntityId(in),
                readEntityId(in),
                readEntityId(in),
                readEntityId(in)
        );
    }

    private static void writeEntityId(DataOutputStream out, EntityId id) throws IOException {
        if (!id.isUuid()) {
            out.writeLong(id.lsb());
        } else {
            out.writeLong(Long.MIN_VALUE);
            out.writeLong(id.msb());
            out.writeLong(id.lsb());
        }
    }

    private static EntityId readEntityId(DataInputStream in) throws IOException {
        long first = in.readLong();
        if (first == Long.MIN_VALUE) {
            return new EntityId(in.readLong(), in.readLong());
        }
        return new EntityId(0L, first);
    }

    private static void writeValue(DataOutputStream out, Value value) throws IOException {
        switch (value) {
            case Value.StringValue(String s) -> {
                out.writeByte(0);
                writeString(out, s);
            }
            case Value.IntValue(long v) -> {
                out.writeByte(1);
                out.writeLong(v);
            }
            case Value.DecimalValue(BigDecimal bd) -> {
                out.writeByte(2);
                byte[] unscaled = bd.unscaledValue().toByteArray();
                out.writeInt(unscaled.length);
                out.write(unscaled);
                out.writeInt(bd.scale());
            }
            case Value.BooleanValue(boolean b) -> {
                out.writeByte(3);
                out.writeBoolean(b);
            }
            case Value.BinaryValue(byte[] bytes) -> {
                out.writeByte(4);
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            case Value.TimestampValue(Instant instant) -> {
                out.writeByte(5);
                out.writeLong(instant.getEpochSecond());
                out.writeInt(instant.getNano());
            }
        }
    }

    private static Value readValue(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case 0 -> Value.of(readString(in));
            case 1 -> Value.of(in.readLong());
            case 2 -> {
                int len = in.readInt();
                if (len <= 0 || len > 10_000_000) {
                    throw new StorageCorruptionException("Invalid decimal byte length: " + len);
                }
                byte[] unscaled = in.readNBytes(len);
                if (unscaled.length != len) {
                    throw new StorageCorruptionException("Truncated decimal value");
                }
                int scale = in.readInt();
                yield Value.of(new BigDecimal(new BigInteger(unscaled), scale));
            }
            case 3 -> Value.of(in.readBoolean());
            case 4 -> {
                int len = in.readInt();
                if (len < 0) {
                    throw new StorageCorruptionException("Negative binary value length: " + len);
                }
                byte[] bytes = in.readNBytes(len);
                if (bytes.length != len) {
                    throw new StorageCorruptionException("Truncated binary value");
                }
                yield Value.of(bytes);
            }
            case 5 -> Value.of(Instant.ofEpochSecond(in.readLong(), in.readInt()));
            default -> throw new StorageCorruptionException("Unknown value tag: " + tag);
        };
    }

    private static void writeOptionalCommitId(DataOutputStream out, Optional<CommitId> commitId) throws IOException {
        out.writeBoolean(commitId.isPresent());
        if (commitId.isPresent()) {
            out.writeLong(commitId.get().value());
        }
    }

    private static Optional<CommitId> readOptionalCommitId(DataInputStream in) throws IOException {
        return in.readBoolean() ? Optional.of(new CommitId(in.readLong())) : Optional.empty();
    }

    private static void writeString(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 100_000_000) {
            throw new StorageCorruptionException("Invalid string length: " + len);
        }
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) {
            throw new StorageCorruptionException("Truncated UTF-8 string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
