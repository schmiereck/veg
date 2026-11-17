package io.veg.engine.persistent;

/**
 * Controls OS buffer flushing behavior when appending frames to the Write-Ahead Log and checkpoint metadata.
 */
public enum DurabilityMode {
    /** Forces channel metadata and data to disk ({@code FileChannel.force(true)}) on every commit and metadata update. */
    FSYNC_EVERY_COMMIT,
    /** Writes to the OS page cache without forcing an immediate physical fsync on every commit (faster for bulk tests). */
    FLUSH_OS_BUFFER
}
