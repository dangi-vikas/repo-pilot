package dev.repopilot.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads analysis runs. Writes go through {@link AnalysisRunner} only. */
public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {

    /**
     * The most recent run of a given pipeline for a repository.
     *
     * <p>"Latest" rather than "latest for the indexed revision" on purpose: after a
     * re-index the newest revision has no findings yet, and falling back to the previous
     * revision's findings would silently attach them to code they were never run against.
     * The run carries its own revision id, so the caller can state which revision the
     * findings describe.
     */
    Optional<AnalysisRun> findFirstByRepositoryIdAndRunTypeOrderByStartedAtDesc(UUID repositoryId, AnalysisRunType runType);

    /**
     * The most recent run of any of several pipelines, for "the newest runtime evidence".
     *
     * <p>Needed because runtime evidence now has more than one reader: a repository can have a JFR
     * recording and a thread dump analysed, and asking for one pipeline by name would report the
     * other as absent.
     */
    Optional<AnalysisRun> findFirstByRepositoryIdAndRunTypeInOrderByStartedAtDesc(UUID repositoryId,
                                                                                Collection<AnalysisRunType> runTypes);

    Optional<AnalysisRun> findFirstByRevisionIdAndRunTypeOrderByStartedAtDesc(UUID revisionId, AnalysisRunType runType);

    List<AnalysisRun> findByRepositoryIdAndRunTypeOrderByStartedAtDesc(UUID repositoryId, AnalysisRunType runType);
}
