package dev.repopilot.runtime.threaddump;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telling one shape of thread dump from another.
 *
 * <p>Every fixture here is the literal opening of a file a JDK 21.0.5 wrote on this machine, because
 * the difference between the shapes is the whole reason this reader refuses two of them:
 * {@code Thread.print} carries thread states and monitor ownership, and {@code Thread.dump_to_file}
 * carries neither, in either of its two formats.
 *
 * <p>Getting this wrong is not a formatting problem. A thin dump read as if it were a rich one would
 * produce a report of zeros — no blocked threads, no contention, no deadlock — and a page of zeros
 * looks exactly like a healthy JVM.
 */
class ThreadDumpFormatTest {

    /** The first lines of a real {@code jcmd <pid> Thread.print} dump. */
    private static final List<String> RICH = List.of(
            "23720:",
            "2026-10-19 10:04:53",
            "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239 mixed mode, sharing):",
            "",
            "Threads class SMR info:",
            "_java_thread_list=0x0000021d4c4c45b0, length=13, elements={");

    /** The first lines of a real {@code Thread.dump_to_file -format=plain} dump of the same JVM. */
    private static final List<String> THIN_PLAIN = List.of(
            "9568",
            "2026-10-19T04:34:15.300714Z",
            "21.0.5+9-LTS-239",
            "",
            "#9 \"Reference Handler\"",
            "      java.base/java.lang.ref.Reference.waitForReferencePendingList(Native Method)");

    /** The first lines of a real {@code Thread.dump_to_file -format=json} dump of the same JVM. */
    private static final List<String> THIN_JSON = List.of(
            "{",
            "  \"threadDump\": {",
            "    \"processId\": \"9568\",",
            "    \"time\": \"2026-10-19T04:32:11.223576900Z\",",
            "    \"runtimeVersion\": \"21.0.5+9-LTS-239\",");

    @Test
    @DisplayName("recognises the rich dump by the header Thread.print writes")
    void recognisesTheRichFormat() {
        assertThat(ThreadDumpFormat.of(RICH)).isEqualTo(ThreadDumpFormat.STACK_TRACE_TEXT);
        assertThat(ThreadDumpFormat.STACK_TRACE_TEXT.readable()).isTrue();
    }

    @Test
    @DisplayName("recognises the thin plain dump, which has no states and no locks in it")
    void recognisesTheThinPlainFormat() {
        assertThat(ThreadDumpFormat.of(THIN_PLAIN)).isEqualTo(ThreadDumpFormat.DUMP_TO_FILE_PLAIN);
        assertThat(ThreadDumpFormat.DUMP_TO_FILE_PLAIN.readable()).isFalse();
    }

    @Test
    @DisplayName("recognises the thin JSON dump by its named top-level object")
    void recognisesTheThinJsonFormat() {
        assertThat(ThreadDumpFormat.of(THIN_JSON)).isEqualTo(ThreadDumpFormat.DUMP_TO_FILE_JSON);
        assertThat(ThreadDumpFormat.DUMP_TO_FILE_JSON.readable()).isFalse();
    }

    @Test
    @DisplayName("does not mistake other JSON for a thread dump")
    void doesNotMistakeOtherJson() {
        // A JMH result, a trace file, a config: JSON that is not a thread dump must not be read as
        // one, because "the file is JSON" is not the same claim as "the file is a thread dump".
        assertThat(ThreadDumpFormat.of(List.of("{", "  \"benchmark\": \"x\",")))
                .isEqualTo(ThreadDumpFormat.UNKNOWN);
    }

    @Test
    @DisplayName("does not mistake other text for a thread dump")
    void doesNotMistakeOtherText() {
        assertThat(ThreadDumpFormat.of(List.of("this is a text file", "with words in it")))
                .isEqualTo(ThreadDumpFormat.UNKNOWN);
        // A bare number alone is not evidence: the thin plain shape needs the instant and a numbered
        // thread header as well.
        assertThat(ThreadDumpFormat.of(List.of("9568", "not a timestamp", "and not threads")))
                .isEqualTo(ThreadDumpFormat.UNKNOWN);
        assertThat(ThreadDumpFormat.of(List.of())).isEqualTo(ThreadDumpFormat.UNKNOWN);
    }

    @Test
    @DisplayName("the refusal names the command that produces a readable dump")
    void theRefusalIsActionable() {
        for (ThreadDumpFormat thin : List.of(ThreadDumpFormat.DUMP_TO_FILE_PLAIN,
                ThreadDumpFormat.DUMP_TO_FILE_JSON)) {
            String explanation = ThreadDumpFormat.explain(thin);
            assertThat(explanation)
                    .contains("Thread.dump_to_file")
                    .contains("not its state")
                    // The answer, not only the problem.
                    .contains("jcmd <pid> Thread.print")
                    .contains("jstack <pid>");
        }
        assertThat(ThreadDumpFormat.explain(ThreadDumpFormat.UNKNOWN))
                .contains("Full thread dump")
                .contains("kill -3");
    }
}
