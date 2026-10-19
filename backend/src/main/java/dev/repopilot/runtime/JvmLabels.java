package dev.repopilot.runtime;

/**
 * How a JVM is written down once, in prose, by every runtime reader.
 *
 * <p>Kept out of the job handlers because more than one reader needs it and the two must not
 * drift: a run summary that said "Java HotSpot(TM) 64-Bit Server VM 21.0.5+9-LTS-239" for a
 * recording and something else for a thread dump of the same VM would be a defect that no
 * compilation error would catch.
 */
public final class JvmLabels {

    private JvmLabels() {
    }

    /**
     * The JVM as one readable phrase, without saying its name twice.
     *
     * <p>{@code jdk.JVMInformation.jvmVersion} is the JVM's full build string, which already
     * begins with the VM name: {@code "Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239) for
     * windows-amd64 JRE (21.0.5+9-LTS-239), built on 2024-09-30T05:17:38Z by …"}. Appending
     * both fields, as the first draft of this summary did, produces the name twice; printing the
     * version whole buries the sentence. So the version becomes its build token and the name is
     * kept once — and both raw values live on in the stored provenance, which is what a reader
     * who wants the file's own words gets.
     *
     * <p>The token is taken from the first parenthesised group that looks like a version — the
     * one containing a digit — because the name itself contains parentheses ({@code "(TM)"}) and
     * the build string contains several groups.
     */
    public static String jvmLabel(String jvmName, String jvmVersion) {
        boolean hasName = jvmName != null && !jvmName.isBlank();
        boolean hasVersion = jvmVersion != null && !jvmVersion.isBlank();
        if (!hasName && !hasVersion) {
            return "an unnamed JVM";
        }
        if (!hasVersion) {
            return jvmName.trim();
        }
        String version = jvmVersion.trim();
        String remainder = hasName && version.startsWith(jvmName.trim())
                ? version.substring(jvmName.trim().length())
                : " " + version;
        String token = versionToken(remainder);
        String detail = token != null ? token : version;
        return hasName ? jvmName.trim() + " " + detail : detail;
    }

    /** The first parenthesised group containing a digit, or null when there is no such group. */
    private static String versionToken(String text) {
        int index = 0;
        while (true) {
            int open = text.indexOf('(', index);
            if (open < 0) {
                return null;
            }
            int close = text.indexOf(')', open + 1);
            if (close < 0) {
                return null;
            }
            String candidate = text.substring(open + 1, close).trim();
            if (candidate.chars().anyMatch(Character::isDigit)) {
                return candidate;
            }
            index = close + 1;
        }
    }
}
