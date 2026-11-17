package io.veg.engine.persistent;

import io.veg.model.Commit;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Append-only framed Write-Ahead Log ({@code wal.log}) protected by {@code CRC32C} checksums and tail trailers.
 *
 * <p>Frame layout: {@code [magic:4][version:2][payloadLength:4][crc32c:4][payload:N][trailer:4]}.</p>
 */
final class WriteAheadLogStore implements Closeable {

    private static final int FRAME_MAGIC = 0x56454757;   // "VEGW"
    private static final int FRAME_TRAILER = 0x454E4443; // "ENDC"
    private static final short FRAME_VERSION = 1;
    private static final int HEADER_BYTES = 14;
    private static final int TRAILER_BYTES = 4;
    private static final int MAX_FRAME_SIZE = 256 * 1024 * 1024;

    record WalRecoveryResult(List<Commit> commits, int truncatedTailFrames) {
    }

    private final Path walPath;
    private FileChannel channel;

    private WriteAheadLogStore(Path walPath, FileChannel channel) {
        this.walPath = Objects.requireNonNull(walPath, "walPath must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
    }

    static WriteAheadLogStore open(Path walPath) throws IOException {
        Objects.requireNonNull(walPath, "walPath must not be null");
        Files.createDirectories(walPath.getParent());
        FileChannel ch = FileChannel.open(
                walPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
        return new WriteAheadLogStore(walPath, ch);
    }

    WalRecoveryResult readAndRecover(boolean truncateDamagedTail) throws IOException {
        List<Commit> commits = new ArrayList<>();
        int truncatedTailFrames = 0;
        long fileSize = channel.size();
        long offset = 0;

        while (offset < fileSize) {
            long remaining = fileSize - offset;
            if (remaining < HEADER_BYTES) {
                if (!truncateDamagedTail) {
                    throw new StorageCorruptionException(
                            "Incomplete WAL frame header at offset " + offset + " (" + remaining + " bytes)");
                }
                truncateAt(offset);
                truncatedTailFrames++;
                break;
            }

            ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_BYTES);
            readFully(channel, headerBuf, offset);
            headerBuf.flip();

            int magic = headerBuf.getInt();
            short version = headerBuf.getShort();
            int payloadLen = headerBuf.getInt();
            int storedCrc = headerBuf.getInt();

            if (magic != FRAME_MAGIC || version != FRAME_VERSION || payloadLen < 0 || payloadLen > MAX_FRAME_SIZE) {
                throw new StorageCorruptionException(
                        "Invalid WAL frame header at offset " + offset
                                + ": magic=0x" + Integer.toHexString(magic)
                                + ", version=" + version + ", payloadLen=" + payloadLen);
            }

            long frameEnd = offset + HEADER_BYTES + (long) payloadLen + TRAILER_BYTES;
            if (frameEnd > fileSize) {
                if (!truncateDamagedTail) {
                    throw new StorageCorruptionException(
                            "Truncated WAL frame payload at offset " + offset
                                    + ": expected end " + frameEnd + ", file size " + fileSize);
                }
                truncateAt(offset);
                truncatedTailFrames++;
                break;
            }

            ByteBuffer payloadAndTrailer = ByteBuffer.allocate(payloadLen + TRAILER_BYTES);
            readFully(channel, payloadAndTrailer, offset + HEADER_BYTES);
            byte[] payload = new byte[payloadLen];
            payloadAndTrailer.flip();
            payloadAndTrailer.get(payload);
            int trailer = payloadAndTrailer.getInt();

            byte[] headerMeta = new byte[6];
            System.arraycopy(headerBuf.array(), 4, headerMeta, 0, 6);
            int actualCrc = computeFrameCrc(headerMeta, payload);
            boolean isTailFrame = (frameEnd == fileSize);

            if (trailer != FRAME_TRAILER || actualCrc != storedCrc) {
                if (isTailFrame && truncateDamagedTail) {
                    truncateAt(offset);
                    truncatedTailFrames++;
                    break;
                }
                throw new StorageCorruptionException(
                        "Corrupted interior WAL frame at offset " + offset
                                + ": expected CRC 0x" + Integer.toHexString(storedCrc)
                                + ", got 0x" + Integer.toHexString(actualCrc)
                                + ", trailer=0x" + Integer.toHexString(trailer));
            }

            Commit commit;
            try {
                commit = BinaryGraphCodec.decodeCommit(payload);
            } catch (StorageCorruptionException ex) {
                if (isTailFrame && truncateDamagedTail) {
                    truncateAt(offset);
                    truncatedTailFrames++;
                    break;
                }
                throw ex;
            }

            commits.add(commit);
            offset = frameEnd;
        }

        channel.position(offset);
        return new WalRecoveryResult(List.copyOf(commits), truncatedTailFrames);
    }

    void append(Commit commit, DurabilityMode durabilityMode, CrashPoint crashPoint) throws IOException {
        byte[] payload = BinaryGraphCodec.encodeCommit(commit);
        ByteBuffer frame = encodeFrame(payload, crashPoint == CrashPoint.CORRUPTED_WAL_TAIL_CRC);

        if (crashPoint == CrashPoint.MID_WAL_FRAME_WRITE) {
            int partialLimit = Math.min(frame.capacity() - 1, HEADER_BYTES + Math.max(1, payload.length / 2));
            frame.limit(partialLimit);
            writeFully(channel, frame);
            if (durabilityMode == DurabilityMode.FSYNC_EVERY_COMMIT) {
                channel.force(true);
            }
            throw new SimulatedCrashException(
                    CrashPoint.MID_WAL_FRAME_WRITE,
                    "Simulated crash mid-way through WAL frame for commit " + commit.id());
        }

        writeFully(channel, frame);
        if (durabilityMode == DurabilityMode.FSYNC_EVERY_COMMIT) {
            channel.force(true);
        }

        if (crashPoint == CrashPoint.CORRUPTED_WAL_TAIL_CRC) {
            throw new SimulatedCrashException(
                    CrashPoint.CORRUPTED_WAL_TAIL_CRC,
                    "Simulated crash leaving corrupted WAL tail CRC32C for commit " + commit.id());
        }
    }

    void rewrite(List<Commit> remainingCommits, DurabilityMode durabilityMode) throws IOException {
        Path tempPath = walPath.resolveSibling(walPath.getFileName() + ".tmp");
        Files.deleteIfExists(tempPath);

        try (FileChannel tmpChannel = FileChannel.open(
                tempPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            for (Commit commit : remainingCommits) {
                byte[] payload = BinaryGraphCodec.encodeCommit(commit);
                writeFully(tmpChannel, encodeFrame(payload, false));
            }
            if (durabilityMode == DurabilityMode.FSYNC_EVERY_COMMIT) {
                tmpChannel.force(true);
            }
        }

        // Close active channel first so atomic file replacement succeeds reliably on Windows.
        channel.close();
        try {
            Files.move(tempPath, walPath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            this.channel = FileChannel.open(
                    walPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            );
            this.channel.position(this.channel.size());
        }
    }

    Path walPath() {
        return walPath;
    }

    long sizeBytes() throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    private ByteBuffer encodeFrame(byte[] payload, boolean corruptCrc) {
        ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + payload.length + TRAILER_BYTES);
        frame.putInt(FRAME_MAGIC);
        frame.putShort(FRAME_VERSION);
        frame.putInt(payload.length);

        byte[] headerMeta = new byte[6];
        ByteBuffer metaBuf = ByteBuffer.wrap(headerMeta);
        metaBuf.putShort(FRAME_VERSION);
        metaBuf.putInt(payload.length);

        int crc = computeFrameCrc(headerMeta, payload);
        if (corruptCrc) {
            crc ^= 0xFFFFFFFF;
        }
        frame.putInt(crc);
        frame.put(payload);
        frame.putInt(FRAME_TRAILER);
        frame.flip();
        return frame;
    }

    private static int computeFrameCrc(byte[] headerMeta, byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(headerMeta, 0, headerMeta.length);
        crc.update(payload, 0, payload.length);
        return (int) crc.getValue();
    }

    private void truncateAt(long offset) throws IOException {
        channel.truncate(offset);
        channel.force(true);
        channel.position(offset);
    }

    private static void readFully(FileChannel ch, ByteBuffer dst, long startPosition) throws IOException {
        long pos = startPosition;
        while (dst.hasRemaining()) {
            int n = ch.read(dst, pos);
            if (n < 0) {
                throw new StorageCorruptionException("Unexpected EOF while reading WAL at position " + pos);
            }
            pos += n;
        }
    }

    private static void writeFully(FileChannel ch, ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            ch.write(src);
        }
    }
}
