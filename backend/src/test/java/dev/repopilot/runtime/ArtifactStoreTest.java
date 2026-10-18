package dev.repopilot.runtime;

import dev.repopilot.shared.error.ErrorCode;
import dev.repopilot.shared.error.RepoPilotException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Storage for uploaded artefacts.
 *
 * <p>Asserts the two properties the rest of the runtime path depends on: that identity comes from
 * the bytes rather than the filename, and that a stored reference cannot be turned back into a path
 * outside the artefact directory. The second is the one that matters most, because
 * {@code storage_ref} is a value from the database being used to open a file.
 */
@SpringBootTest(properties = {
        "repopilot.security.token=integration-test-bearer-token-0003",
        "repopilot.ai.enabled=false"
})
class ArtifactStoreTest {

    @TempDir
    static Path workspaceRoot;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("repopilot.workspace.roots", () -> workspaceRoot.toAbsolutePath().toString());
        registry.add("repopilot.storage.data-dir", () -> dataDir.toAbsolutePath().toString());
    }

    @Autowired
    private ArtifactStore store;

    @Test
    @DisplayName("stores by content hash and reuses the bytes when the same file is uploaded twice")
    void storesByContent() throws IOException {
        UUID repositoryId = UUID.randomUUID();
        byte[] content = "FLR\u0000pretend recording".getBytes(StandardCharsets.UTF_8);

        Path first = writeIncoming(content);
        ArtifactStore.StoredArtifact stored = store.adopt(repositoryId, RuntimeArtifactType.JFR_RECORDING, first);

        assertThat(stored.contentHash()).isEqualTo(sha256(content));
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.storageRef()).startsWith(repositoryId.toString() + "/");
        assertThat(stored.storageRef()).endsWith(".jfr");
        assertThat(Files.readAllBytes(store.resolve(stored.storageRef()))).isEqualTo(content);
        // The scratch file is consumed, never left behind for the next upload to collide with.
        assertThat(Files.exists(first)).isFalse();

        // A second upload of the same bytes is the same artefact, not a second copy.
        Path second = writeIncoming(content);
        ArtifactStore.StoredArtifact again = store.adopt(repositoryId, RuntimeArtifactType.JFR_RECORDING, second);
        assertThat(again.storageRef()).isEqualTo(stored.storageRef());
        assertThat(Files.exists(second)).isFalse();

        Path other = writeIncoming("FLR\u0000different".getBytes(StandardCharsets.UTF_8));
        ArtifactStore.StoredArtifact distinct = store.adopt(repositoryId, RuntimeArtifactType.JFR_RECORDING, other);
        assertThat(distinct.contentHash()).isNotEqualTo(stored.contentHash());
        assertThat(distinct.storageRef()).isNotEqualTo(stored.storageRef());
    }

    @Test
    @DisplayName("refuses a stored reference that points outside the artefact directory")
    void refusesToEscape() {
        // Every one of these is a path that resolves somewhere else. Lexical containment is what
        // stops them, and a symlink is stopped by the real-path check that follows it.
        for (String hostile : new String[]{
                "../outside.jfr",
                "../../../etc/passwd",
                "..\\..\\Windows\\win.ini",
                "/etc/passwd",
                "a/../../b.jfr"}) {
            assertThat(codeOf(hostile)).as(hostile)
                    .isIn(ErrorCode.PATH_REJECTED, ErrorCode.NOT_FOUND, ErrorCode.VALIDATION_FAILED);
        }

        assertThat(codeOf(null)).isEqualTo(ErrorCode.VALIDATION_FAILED);

        // A well-formed reference to a file that is not there is a missing artefact, not an attack.
        assertThat(codeOf("00000000-0000-0000-0000-000000000000/ab/absent.jfr"))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    /** The refusal code for a reference, failing the test if it is accepted at all. */
    private ErrorCode codeOf(String storageRef) {
        try {
            store.resolve(storageRef);
            throw new AssertionError("expected " + storageRef + " to be refused");
        } catch (RepoPilotException refused) {
            return refused.errorCode();
        }
    }

    @Test
    @DisplayName("keeps scratch uploads inside the data directory")
    void keepsScratchFilesInsideTheDataDirectory() throws IOException {
        Path scratch = store.newIncomingFile();

        assertThat(Files.exists(scratch)).isTrue();
        assertThat(scratch.toAbsolutePath().normalize()).startsWith(dataDir.toAbsolutePath().normalize());
        assertThat(store.artifactsDirectory().toAbsolutePath().normalize())
                .startsWith(dataDir.toAbsolutePath().normalize());

        Files.deleteIfExists(scratch);
    }

    private Path writeIncoming(byte[] content) throws IOException {
        Path file = store.newIncomingFile();
        Files.write(file, content);
        return file;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
