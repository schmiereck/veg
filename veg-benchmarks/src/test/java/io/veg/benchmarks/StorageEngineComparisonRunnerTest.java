package io.veg.benchmarks;

import io.veg.benchmarks.StorageEngineComparisonRunner.ComparisonConfig;
import io.veg.benchmarks.StorageEngineComparisonRunner.ComparisonReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StorageEngineComparisonRunnerTest {

    @Test
    void runsAllFourEnginesAcrossWorkloadsW1ToW7AndExportsCsvAndJson(@TempDir Path tempDir) throws Exception {
        StorageEngineComparisonRunner runner = new StorageEngineComparisonRunner();
        ComparisonConfig config = new ComparisonConfig(80, 12, 30, 42L, 0, 30);

        ComparisonReport report = runner.runAllEngines(config);
        assertThat(report.results()).hasSize(4)
                .extracting(StorageEngineComparisonRunner.EngineResult::engineName)
                .containsExactly(
                        "ENGINE_A_HASHMAP",
                        "ENGINE_B1_COMPACT_CSR",
                        "ENGINE_B2_JOURNAL_CKPT10",
                        "ENGINE_C_PERSISTENT_TREE"
                );

        assertThat(report.results()).allSatisfy(r -> {
            assertThat(r.entitiesAfterBase()).isEqualTo(report.results().getFirst().entitiesAfterBase());
            assertThat(r.edgesAfterBase()).isEqualTo(report.results().getFirst().edgesAfterBase());
            assertThat(r.totalCommits()).isEqualTo(report.results().getFirst().totalCommits()).isGreaterThanOrEqualTo(14L);
            assertThat(r.totalBranches()).isEqualTo(31L);
            assertThat(r.w2CommitThroughputPerSec()).isGreaterThan(0.0);
            assertThat(r.w4BranchesMs()).isGreaterThanOrEqualTo(0.0);
        });

        Path csv = tempDir.resolve("storage.csv");
        Path json = tempDir.resolve("storage.json");
        StorageEngineComparisonRunner.writeReportCsv(report, csv);
        StorageEngineComparisonRunner.writeReportJson(report, json);

        assertThat(Files.readString(csv)).contains("ENGINE_A_HASHMAP", "ENGINE_C_PERSISTENT_TREE", "w7_full_replay_ms");
        assertThat(Files.readString(json)).contains("\"engineName\": \"ENGINE_B1_COMPACT_CSR\"", "\"results\": [");
    }
}
