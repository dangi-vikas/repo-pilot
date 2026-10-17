package dev.repopilot.jobs;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps {@link JobType} to its {@link JobHandler}.
 *
 * <p>A duplicate handler for the same type is a startup failure: two implementations
 * of the same job is always a wiring mistake, and silently picking one would make
 * behaviour depend on bean ordering. A type with no handler is reported as
 * unsupported at submission time instead of being accepted and left queued forever.
 */
@Component
public class JobRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);

    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);

    public JobRegistry(List<JobHandler> jobHandlers) {
        for (JobHandler handler : jobHandlers) {
            JobHandler previous = handlers.putIfAbsent(handler.jobType(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two handlers are registered for job type " + handler.jobType() + ": "
                                + previous.getClass().getName() + " and " + handler.getClass().getName()
                                + ". Exactly one handler per job type is permitted.");
            }
        }
    }

    @PostConstruct
    void reportCoverage() {
        Set<JobType> missing = EnumSet.allOf(JobType.class);
        missing.removeAll(handlers.keySet());
        if (missing.isEmpty()) {
            log.info("job registry ready: all {} job types have a handler", handlers.size());
        } else {
            // Not a failure: RepoPilot is built incrementally and a missing handler for an
            // advanced analysis type is a legitimate partial delivery. Submitting those
            // types returns a clear UNSUPPORTED_INPUT error rather than queueing forever.
            log.warn("job registry ready: {} of {} job types have a handler. Unsupported: {}",
                    handlers.size(), JobType.values().length, missing);
        }
    }

    public boolean isSupported(JobType type) {
        return handlers.containsKey(type);
    }

    public JobHandler require(JobType type) {
        JobHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("No handler registered for job type " + type);
        }
        return handler;
    }

    public Set<JobType> supportedTypes() {
        return Set.copyOf(handlers.keySet());
    }
}
