package io.veg.benchmarks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared telemetry, percentile, heap estimation, and report serialization helpers for VEG benchmarks.
 */
final class BenchmarkMetricsSupport {

    private BenchmarkMetricsSupport() {
    }

    static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0L;
        }
        int index = (int) Math.ceil(p * sorted.length) - 1;
        int clamped = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[clamped];
    }

    static long settledUsedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 2; i++) {
            System.gc();
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    static void writeUtf8File(Path outputPath, String content) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputPath, content, StandardCharsets.UTF_8);
    }

    static String csvEscape(String raw) {
        if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")) {
            return '"' + raw.replace("\"", "\"\"") + '"';
        }
        return raw;
    }

    static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
