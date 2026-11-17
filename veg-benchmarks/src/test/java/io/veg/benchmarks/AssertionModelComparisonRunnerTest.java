package io.veg.benchmarks;

import io.veg.benchmarks.AssertionModelComparisonRunner.AssertionModel;
import io.veg.benchmarks.AssertionModelComparisonRunner.ExperimentConfig;
import io.veg.benchmarks.AssertionModelComparisonRunner.ExperimentReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionModelComparisonRunnerTest {

    @Test
    void comparesBothAssertionModelsWithIdenticalQueryResultsAndSerializesReports(@TempDir Path tempDir) throws Exception {
        AssertionModelComparisonRunner runner = new AssertionModelComparisonRunner();
        ExperimentConfig config = new ExperimentConfig(40, 42L, List.of(1, 2), 1, 1, 30);

        ExperimentReport report = runner.runInMemoryComparison(config);

        assertThat(report.results()).hasSize(4);
        var optA1 = report.results().get(0);
        var optB1 = report.results().get(1);
        assertThat(optA1.model()).isEqualTo(AssertionModel.CONNECTION_PLUS_ASSERTIONS);
        assertThat(optB1.model()).isEqualTo(AssertionModel.EDGE_AS_ASSERTION);
        assertThat(optA1.matchedFilterCount()).isEqualTo(optB1.matchedFilterCount());
        assertThat(optB1.entitiesTotal()).isLessThan(optA1.entitiesTotal());
        assertThat(optB1.edgesTotal()).isLessThan(optA1.edgesTotal());
        assertThat(optA1.heapDeltaBytes()).isPositive();
        assertThat(optB1.heapDeltaBytes()).isPositive();

        Path csv = tempDir.resolve("assertions.csv");
        Path json = tempDir.resolve("assertions.json");
        AssertionModelComparisonRunner.writeReportCsv(report, csv);
        AssertionModelComparisonRunner.writeReportJson(report, json);

        assertThat(Files.readString(csv)).contains("CONNECTION_PLUS_ASSERTIONS", "EDGE_AS_ASSERTION");
        assertThat(Files.readString(json)).contains("\"assertionsPerPair\": 2", "\"matchedFilterCount\":");
    }
}
