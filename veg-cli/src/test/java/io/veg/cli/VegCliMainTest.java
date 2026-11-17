package io.veg.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VegCliMainTest {

    @Test
    void demoCommandOutputsStage1Verification() throws Exception {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        int code = VegCliMain.execute(
                new String[]{"demo"},
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        String stdout = outBuf.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("T01:", "T02:", "T03:", "T04:");
    }

    @Test
    void benchmarkCommandWritesCsvAndJson(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("bench.csv");
        Path json = tempDir.resolve("bench.json");
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        int code = VegCliMain.execute(
                new String[]{
                        "benchmark",
                        "--entities", "50",
                        "--seed", "42",
                        "--warmup", "1",
                        "--iterations", "1",
                        "--queries", "20",
                        "--output", csv.toString(),
                        "--json", json.toString()
                },
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        assertThat(Files.exists(csv)).isTrue();
        assertThat(Files.exists(json)).isTrue();
    }
}
