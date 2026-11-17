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
        assertThat(stdout).contains(
                "T01:", "T02:", "T03:", "T04:",
                "VEG Stage 3", "replay matches live=true",
                "VEG Stage 4", "Merge succeeded=true",
                "VEG Stage 5", "HISTORICAL_HEAD_DENY",
                "VEG Stage 6", "LUBM imported objects=42", "BSBM imported objects=32", "reifiedAnnotations=2"
        );
    }

    @Test
    void importRdfCommandImportsQueriesAndExportsTurtle(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("sample.ttl");
        Path output = tempDir.resolve("exported.ttl");
        Files.writeString(input, """
                @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix ex:   <http://example.org/> .
                ex:p1 a ex:Product ; ex:productName "Alpha" ; ex:price 49.90 .
                [ a rdf:Statement ; rdf:subject ex:p1 ; rdf:predicate ex:productName ; rdf:object "Alpha" ; ex:hasConfidence 0.99 ] .
                """, StandardCharsets.UTF_8);

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = VegCliMain.execute(
                new String[]{
                        "import-rdf",
                        "--input", input.toString(),
                        "--query", "MATCH (p:Product) WHERE p.price <= 100.0 SELECT p.productName, p.price",
                        "--export", output.toString()
                },
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        String stdout = outBuf.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("Imported RDF (TURTLE): objects=1", "Query rows=1", "Alpha");
        assertThat(Files.exists(output)).isTrue();
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).contains("rdf:Statement", "hasConfidence");
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

    @Test
    void assertionExperimentCommandWritesCsvAndJson(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("assertions.csv");
        Path json = tempDir.resolve("assertions.json");
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        int code = VegCliMain.execute(
                new String[]{
                        "assertion-experiment",
                        "--pairs", "30",
                        "--seed", "42",
                        "--densities", "1,2",
                        "--warmup", "1",
                        "--iterations", "1",
                        "--queries", "15",
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
