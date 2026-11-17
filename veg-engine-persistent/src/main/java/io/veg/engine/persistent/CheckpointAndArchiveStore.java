package io.veg.engine.persistent;

import io.veg.engine.memory.PersistentGraphSnapshot;
import io.veg.model.Commit;
import io.veg.model.CommitId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Manages atomic {@code refs.meta}, compressed {@code checkpoints/ckpt-*.bin.gz} snapshots,
 * and compressed {@code archive/segment-*.wal.gz} cold history segments protected by {@code CRC32C}.
 */
final class CheckpointAndArchiveStore {

    private static final String REFS_FILE = "refs.meta";
    private static final String CHECKPOINTS_DIR = "checkpoints";
    private static final String ARCHIVE_DIR = "archive";
    private static final String CKPT_PREFIX = "ckpt-";
    private static final String CKPT_SUFFIX = ".bin.gz";
    private static final String SEGMENT_PREFIX = "segment-";
    private static final String SEGMENT_SUFFIX = ".wal.gz";

    record ArchiveSegment(long fromCommit, long toCommit, Path path) {
    }

    private final Path dataDir;
    private final Path refsPath;
    private final Path checkpointsDir;
    private final Path archiveDir;

    CheckpointAndArchiveStore(Path dataDir) throws IOException {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir must not be null");
        this.refsPath = dataDir.resolve(REFS_FILE);
        this.checkpointsDir = dataDir.resolve(CHECKPOINTS_DIR);
        this.archiveDir = dataDir.resolve(ARCHIVE_DIR);
        Files.createDirectories(this.dataDir);
        Files.createDirectories(this.checkpointsDir);
        Files.createDirectories(this.archiveDir);
    }

    int cleanupTempFiles() throws IOException {
        int cleaned = 0;
        for (Path dir : List.of(dataDir, checkpointsDir, archiveDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList()) {
                    if (Files.deleteIfExists(p)) {
                        cleaned++;
                    }
                }
            }
        }
        return cleaned;
    }

    boolean hasRefsMeta() {
        return Files.exists(refsPath);
    }

    Optional<BinaryGraphCodec.RefsMetadata> readRefsMeta() throws IOException {
        if (!Files.exists(refsPath)) {
            return Optional.empty();
        }
        byte[] raw = Files.readAllBytes(refsPath);
        return Optional.of(BinaryGraphCodec.decodeRefsMeta(raw));
    }

    void writeRefsMeta(BinaryGraphCodec.RefsMetadata meta, DurabilityMode mode) throws IOException {
        writeAtomicBytes(refsPath, BinaryGraphCodec.encodeRefsMeta(meta), mode);
    }

    Path checkpointPath(CommitId commitId) {
        return checkpointsDir.resolve(CKPT_PREFIX + commitId.value() + CKPT_SUFFIX);
    }

    boolean hasCheckpoint(CommitId commitId) {
        return Files.isRegularFile(checkpointPath(commitId));
    }

    void writeCheckpoint(
            CommitId commitId,
            PersistentGraphSnapshot snapshot,
            DurabilityMode mode,
            CrashPoint crashPoint
    ) throws IOException {
        byte[] uncompressed = BinaryGraphCodec.encodeSnapshot(commitId, snapshot);
        byte[] packaged = BinaryGraphCodec.compressEnvelope(
                BinaryGraphCodec.MAGIC_CHECKPOINT,
                uncompressed,
                "checkpoint " + commitId
        );
        Path target = checkpointPath(commitId);

        if (crashPoint == CrashPoint.MID_CHECKPOINT_WRITE) {
            Path tempPath = target.resolveSibling(target.getFileName() + ".tmp");
            int half = Math.max(1, packaged.length / 2);
            byte[] partial = new byte[half];
            System.arraycopy(packaged, 0, partial, 0, half);
            writeTempBytes(tempPath, partial, mode);
            throw new SimulatedCrashException(
                    CrashPoint.MID_CHECKPOINT_WRITE,
                    "Simulated crash mid-way through writing checkpoint " + commitId);
        }

        writeAtomicBytes(target, packaged, mode);
    }

    BinaryGraphCodec.DecodedSnapshot readCheckpoint(CommitId commitId) throws IOException {
        Path path = checkpointPath(commitId);
        if (!Files.isRegularFile(path)) {
            throw new StorageCorruptionException("Missing checkpoint file: " + path.getFileName());
        }
        String label = "checkpoint " + path.getFileName();
        byte[] raw = Files.readAllBytes(path);
        byte[] uncompressed = BinaryGraphCodec.decompressEnvelope(raw, BinaryGraphCodec.MAGIC_CHECKPOINT, label);
        BinaryGraphCodec.DecodedSnapshot decoded = BinaryGraphCodec.decodeSnapshot(uncompressed, label);
        if (!decoded.commitId().equals(commitId)) {
            throw new StorageCorruptionException(
                    "Checkpoint " + path.getFileName() + " contains mismatched commitId " + decoded.commitId());
        }
        return decoded;
    }

    List<CommitId> listCheckpointIds() throws IOException {
        if (!Files.isDirectory(checkpointsDir)) {
            return List.of();
        }
        List<CommitId> ids = new ArrayList<>();
        try (Stream<Path> files = Files.list(checkpointsDir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith(CKPT_PREFIX) && name.endsWith(CKPT_SUFFIX)) {
                    String numStr = name.substring(CKPT_PREFIX.length(), name.length() - CKPT_SUFFIX.length());
                    try {
                        ids.add(new CommitId(Long.parseLong(numStr)));
                    } catch (NumberFormatException ignored) {
                        // Ignore non-checkpoint auxiliary files.
                    }
                }
            }
        }
        ids.sort(Comparator.naturalOrder());
        return List.copyOf(ids);
    }

    void writeArchiveSegment(
            long fromCommit,
            long toCommit,
            List<Commit> commits,
            DurabilityMode mode
    ) throws IOException {
        byte[] batch = BinaryGraphCodec.encodeCommitBatch(commits);
        String label = "archive segment " + fromCommit + "-" + toCommit;
        byte[] packaged = BinaryGraphCodec.compressEnvelope(BinaryGraphCodec.MAGIC_ARCHIVE, batch, label);
        Path target = archiveDir.resolve(SEGMENT_PREFIX + fromCommit + "-" + toCommit + SEGMENT_SUFFIX);
        writeAtomicBytes(target, packaged, mode);
    }

    List<ArchiveSegment> listArchiveSegments() throws IOException {
        if (!Files.isDirectory(archiveDir)) {
            return List.of();
        }
        List<ArchiveSegment> segments = new ArrayList<>();
        try (Stream<Path> files = Files.list(archiveDir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith(SEGMENT_PREFIX) && name.endsWith(SEGMENT_SUFFIX)) {
                    String range = name.substring(SEGMENT_PREFIX.length(), name.length() - SEGMENT_SUFFIX.length());
                    int dash = range.indexOf('-');
                    if (dash > 0) {
                        try {
                            long from = Long.parseLong(range.substring(0, dash));
                            long to = Long.parseLong(range.substring(dash + 1));
                            segments.add(new ArchiveSegment(from, to, p));
                        } catch (NumberFormatException ignored) {
                            // Ignore unrelated files.
                        }
                    }
                }
            }
        }
        segments.sort(Comparator.comparingLong(ArchiveSegment::fromCommit)
                .thenComparingLong(ArchiveSegment::toCommit));
        return List.copyOf(segments);
    }

    List<Commit> readArchiveSegment(ArchiveSegment segment) throws IOException {
        String label = "archive " + segment.path().getFileName();
        byte[] raw = Files.readAllBytes(segment.path());
        byte[] uncompressed = BinaryGraphCodec.decompressEnvelope(raw, BinaryGraphCodec.MAGIC_ARCHIVE, label);
        return BinaryGraphCodec.decodeCommitBatch(uncompressed, label);
    }

    List<Commit> readAllArchivedCommits() throws IOException {
        List<Commit> result = new ArrayList<>();
        for (ArchiveSegment seg : listArchiveSegments()) {
            result.addAll(readArchiveSegment(seg));
        }
        return List.copyOf(result);
    }

    private static void writeAtomicBytes(Path target, byte[] bytes, DurabilityMode mode) throws IOException {
        Path tempPath = target.resolveSibling(target.getFileName() + ".tmp");
        writeTempBytes(tempPath, bytes, mode);
        Files.move(tempPath, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeTempBytes(Path tempPath, byte[] bytes, DurabilityMode mode) throws IOException {
        Files.createDirectories(tempPath.getParent());
        try (FileChannel ch = FileChannel.open(
                tempPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            if (mode == DurabilityMode.FSYNC_EVERY_COMMIT) {
                ch.force(true);
            }
        }
    }
}
