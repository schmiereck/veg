package io.veg.engine.persistent;

import io.veg.model.CommitId;

import java.util.Objects;
import java.util.Optional;

/**
 * Summary of actions performed while opening and recovering a {@link PersistentDiskVersionedDatabase}.
 *
 * @param replayedWalCommits       number of valid WAL commits replayed on top of the loaded checkpoint/archive
 * @param recoveredUnindexedCommits number of valid tail WAL commits whose branch heads were missing from {@code refs.meta}
 * @param truncatedTailFrames      number of torn or invalid-CRC tail frames truncated from {@code wal.log} ({@code T17})
 * @param cleanedTempFiles         number of orphaned {@code *.tmp} files cleaned up during startup
 * @param loadedCheckpointId       checkpoint commit ID loaded at startup, if any
 */
public record RecoveryReport(
        int replayedWalCommits,
        int recoveredUnindexedCommits,
        int truncatedTailFrames,
        int cleanedTempFiles,
        Optional<CommitId> loadedCheckpointId
) {
    public RecoveryReport {
        Objects.requireNonNull(loadedCheckpointId, "loadedCheckpointId must not be null");
    }
}
