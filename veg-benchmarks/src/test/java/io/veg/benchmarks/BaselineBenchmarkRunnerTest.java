package io.veg.benchmarks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineBenchmarkRunnerTest {

    @Test
    void runsSmallSmokeBenchmarkAndFormatsCsvAndJson(@TempDir Path tempDir) throws Exception {
        BaselineBenchmarkRunner runner = new BaselineBenchmarkRunner();
        BaselineBenchmarkRunner.BenchmarkConfig config =
                new BaselineBenchmarkRunner.BenchmarkConfig(100, 42L, 0.5, 0.25, 1, 2, 50);

        BaselineBenchmarkRunner.BenchmarkReport report = runner.runInMemoryBaseline(config);

        assertThat(report.iterations()).hasSize(2);
        assertThat(report.iterations().getFirst().entitiesTotal()).isGreaterThan(100L);
        assertThat(report.iterations().getFirst().edgesTotal()).isGreaterThan(100L);
        assertThat(report.iterations().getFirst().writeOpsPerSec()).isPositive();

        Path csv = tempDir.resolve("results.csv");
        Path json = tempDir.resolve("results.json");
        BaselineBenchmarkRunner.writeReportCsv(report, csv);
        BaselineBenchmarkRunner.writeReportJson(report, json);

        assertThat(Files.readString(csv)).contains("InMemoryGraphEngine", "write_ops_per_sec");
        assertThat(Files.readString(json)).contains("\"engineName\": \"InMemoryGraphEngine\"", "\"iterations\": [");
    }
}
