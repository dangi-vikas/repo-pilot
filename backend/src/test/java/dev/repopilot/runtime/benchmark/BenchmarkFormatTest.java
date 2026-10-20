package dev.repopilot.runtime.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape classification, checked against the bytes each writer actually produces.
 *
 * <p>Every case here is a real shape: the array {@code -rf json} writes, the object a hand-edited
 * extract is, the quoted header row {@code -rf csv} writes, the {@code # JMH version:} block
 * {@code -rf text} writes, and a gzip stream. The gzip case matters most, because a compressed stream
 * decoded as text is mojibake and the useful answer is "decompress it", not "this is not a benchmark".
 */
class BenchmarkFormatTest {

    @Test
    @DisplayName("the array -rf json writes is the one readable shape")
    void classifiesTheJsonArrayJmhWrites() {
        BenchmarkFormat format = of("[{\"jmhVersion\" : \"1.37\"}]");

        assertThat(format).isEqualTo(BenchmarkFormat.JSON_ARRAY);
        assertThat(format.readable()).isTrue();
    }

    @Test
    @DisplayName("a single JSON object is classified separately from the array")
    void classifiesAnObjectAsItsOwnShape() {
        BenchmarkFormat format = of("{\"benchmark\" : \"dev.repopilot.fixture.Work.run\"}");

        assertThat(format).isEqualTo(BenchmarkFormat.JSON_OBJECT);
        assertThat(format.readable()).isFalse();
    }

    @Test
    @DisplayName("JMH's CSV output is recognised by its quoted header row")
    void classifiesTheCsvOutput() {
        assertThat(of("\"Benchmark\",\"Mode\",\"Threads\",\"Score\"\n\"dev.repopilot.Work.run\",\"thrpt\",1,9.8"))
                .isEqualTo(BenchmarkFormat.CSV);
    }

    @Test
    @DisplayName("JMH's text output is recognised by the comment block it opens with")
    void classifiesTheTextOutput() {
        assertThat(of("""
                # JMH version: 1.37
                # VM version: JDK 21.0.5, Java HotSpot(TM) 64-Bit Server VM, 21.0.5+9-LTS-239
                # Benchmark mode: Throughput, ops/time
                # Benchmark: dev.repopilot.fixture.Work.run
                """))
                .isEqualTo(BenchmarkFormat.TEXT);
    }

    @Test
    @DisplayName("gzip is recognised by its two magic bytes before any text decoding")
    void classifiesGzipByItsMagic() throws IOException {
        assertThat(BenchmarkFormat.of(gzip("[]"))).isEqualTo(BenchmarkFormat.GZIP);
    }

    @Test
    @DisplayName("an empty or whitespace-only file is its own answer")
    void classifiesAnEmptyFile() {
        assertThat(of("")).isEqualTo(BenchmarkFormat.EMPTY);
        assertThat(of(" \r\n\t ")).isEqualTo(BenchmarkFormat.EMPTY);
    }

    @Test
    @DisplayName("a byte-order mark does not hide the opening bracket")
    void skipsAByteOrderMark() {
        assertThat(of("\uFEFF[{\"jmhVersion\" : \"1.37\"}]")).isEqualTo(BenchmarkFormat.JSON_ARRAY);
    }

    @Test
    @DisplayName("anything else is unknown rather than guessed at")
    void refusesWhatItDoesNotRecognise() {
        assertThat(of("PK\u0003\u0004 a zip of something")).isEqualTo(BenchmarkFormat.UNKNOWN);
        assertThat(of("Benchmark;Mode;Threads\n")).isEqualTo(BenchmarkFormat.UNKNOWN);
        assertThat(of("suite name, score, unit\n")).isEqualTo(BenchmarkFormat.UNKNOWN);
    }

    @Test
    @DisplayName("only the array shape is readable")
    void onlyTheArrayIsReadable() {
        for (BenchmarkFormat format : BenchmarkFormat.values()) {
            assertThat(format.readable()).isEqualTo(format == BenchmarkFormat.JSON_ARRAY);
        }
    }

    @Test
    @DisplayName("every refusal names the command that produces a readable file")
    void everyExplanationIsActionable() {
        assertThat(BenchmarkFormat.explain(BenchmarkFormat.CSV))
                .contains("-rf json")
                .contains("per-iteration");
        assertThat(BenchmarkFormat.explain(BenchmarkFormat.TEXT))
                .contains("-rf json")
                .contains("rounded");
        assertThat(BenchmarkFormat.explain(BenchmarkFormat.JSON_OBJECT))
                .contains("-rf json")
                .contains("array");
        assertThat(BenchmarkFormat.explain(BenchmarkFormat.GZIP))
                .contains("gzip -d");
        assertThat(BenchmarkFormat.explain(BenchmarkFormat.UNKNOWN))
                .contains("-rf json")
                .contains("primaryMetric");
        assertThat(BenchmarkFormat.explain(BenchmarkFormat.EMPTY))
                .contains("empty");
    }

    private static BenchmarkFormat of(String text) {
        return BenchmarkFormat.of(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
