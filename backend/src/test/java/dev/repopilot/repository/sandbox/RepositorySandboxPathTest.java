package dev.repopilot.repository.sandbox;

import dev.repopilot.shared.error.ErrorCode;
import dev.repopilot.shared.error.RepoPilotException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security tests for the syntactic half of the repository sandbox.
 *
 * <p>These run without a filesystem, which is the point: rejection must happen on the
 * shape of the path before anything is touched, so a slow or hostile filesystem cannot
 * cause a fall-through to a read.
 */
class RepositorySandboxPathTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "../etc/passwd",
            "a/../../b",
            "/etc/passwd",
            "C:/Windows/system32",
            "C:\\Windows\\system32",
            "\\\\server\\share\\file",
            "dir/file\0.java",
            "a/*/b",
            "a/?.java",
            "a/|b"
    })
    @DisplayName("rejects traversal, absolute, drive-qualified, UNC and metacharacter paths")
    void rejectsUnsafePaths(String candidate) {
        assertThatThrownBy(() -> RepositorySandbox.validateRelativePath(candidate))
                .isInstanceOf(RepoPilotException.class)
                .extracting(e -> ((RepoPilotException) e).errorCode())
                .isEqualTo(ErrorCode.PATH_REJECTED);
    }

    @Test
    @DisplayName("normalises harmless separators and redundant segments")
    void normalisesSafePaths() {
        assertThat(RepositorySandbox.validateRelativePath("src\\main/./App.java"))
                .isEqualTo("src/main/App.java");
        assertThat(RepositorySandbox.validateRelativePath("a//b/c.java")).isEqualTo("a/b/c.java");
    }

    @Test
    @DisplayName("rejects a blank or missing path as a validation failure, not a traversal")
    void rejectsBlank() {
        assertThatThrownBy(() -> RepositorySandbox.validateRelativePath("  "))
                .isInstanceOf(RepoPilotException.class)
                .extracting(e -> ((RepoPilotException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
