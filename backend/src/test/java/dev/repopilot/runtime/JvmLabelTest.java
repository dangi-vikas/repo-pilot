package dev.repopilot.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JVM label in a run summary.
 *
 * <p>This is a small piece of prose with a real failure mode: the JDK's
 * {@code jdk.JVMInformation.jvmVersion} is the full build string and begins with the VM name, so
 * the obvious implementation prints the name twice, and anchoring on the first parenthesis prints
 * {@code "TM"}.
 *
 * <p>It lives on {@link JvmLabels} rather than on one handler because two readers write it — a
 * recording's provenance and a thread dump's header are the same JVM described two different ways
 * — and a summary that disagreed with itself depending on which file was uploaded would be a
 * defect nothing would flag.
 */
class JvmLabelTest {

    /** The values JDK 21.0.5 reports on this machine, copied verbatim from a real recording. */
    private static final String HOTSPOT = "Java HotSpot(TM) 64-Bit Server VM";
    private static final String HOTSPOT_VERSION = "Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239) "
            + "for windows-amd64 JRE (21.0.5+9-LTS-239), built on 2024-09-30T05:17:38Z by \"mach5one\" "
            + "with MS VC++ 17.6 (VS2022)";

    @Test
    @DisplayName("does not say the VM name twice when the version already contains it")
    void doesNotRepeatTheName() {
        assertThat(JvmLabels.jvmLabel(HOTSPOT, HOTSPOT_VERSION))
                .isEqualTo("Java HotSpot(TM) 64-Bit Server VM 21.0.5+9-LTS-239");
    }

    @Test
    @DisplayName("finds the version, not the (TM) in the VM name")
    void findsTheVersionNotTheTrademark() {
        // With no name to anchor on, the first parenthesised group is "(TM)". The token has to be
        // chosen by shape, not by position.
        assertThat(JvmLabels.jvmLabel(null, HOTSPOT_VERSION))
                .isEqualTo("21.0.5+9-LTS-239");
        assertThat(JvmLabels.jvmLabel("  ", HOTSPOT_VERSION))
                .isEqualTo("21.0.5+9-LTS-239");
    }

    @Test
    @DisplayName("falls back to what is present when one field is missing")
    void fallsBackWhenAFieldIsMissing() {
        assertThat(JvmLabels.jvmLabel(HOTSPOT, null)).isEqualTo(HOTSPOT);
        assertThat(JvmLabels.jvmLabel(HOTSPOT, "  ")).isEqualTo(HOTSPOT);
        // No parentheses at all: the version is used as it stands rather than mangled. A thread
        // dump's header is what produces this shape: the build token is already bare there.
        assertThat(JvmLabels.jvmLabel("OpenJDK 64-Bit Server VM", "25-beta"))
                .isEqualTo("OpenJDK 64-Bit Server VM 25-beta");
        assertThat(JvmLabels.jvmLabel("Java HotSpot(TM) 64-Bit Server VM", "21.0.5+9-LTS-239"))
                .isEqualTo("Java HotSpot(TM) 64-Bit Server VM 21.0.5+9-LTS-239");
        // A version that does not begin with the name is kept whole alongside it.
        assertThat(JvmLabels.jvmLabel("Eclipse OpenJ9 VM", "OpenJ9 (0.43.0) build 21"))
                .isEqualTo("Eclipse OpenJ9 VM 0.43.0");
    }

    @Test
    @DisplayName("says so when the recording names no JVM")
    void saysWhenNothingIsKnown() {
        assertThat(JvmLabels.jvmLabel(null, null)).isEqualTo("an unnamed JVM");
        assertThat(JvmLabels.jvmLabel("  ", "  ")).isEqualTo("an unnamed JVM");
    }
}
