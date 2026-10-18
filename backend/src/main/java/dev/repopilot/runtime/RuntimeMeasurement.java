package dev.repopilot.runtime;

import dev.repopilot.shared.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One number observed while a process was running.
 *
 * <p>A measurement is not a finding and is not a fact about source code. It is a value with
 * a unit, a reduction, a sample count and a scope, tied to the run that derived it and (for
 * JFR) to the recording it came from. The distinction is the whole point of the evidence
 * model: "this method was on the stack in 47 of 51 samples" is a measurement of a recording;
 * "this method is slow" would be a conclusion, and RepoPilot does not store conclusions it
 * has not drawn explicitly.
 *
 * <p>{@code scope} says what the value is <em>over</em>: a method for a hot-frame metric, a
 * class for an allocation metric, a GC cause for a collection breakdown, null for a
 * repository-wide total. {@code sampleCount} says how many observations were reduced, which
 * is what makes a maximum of one sample distinguishable from a maximum of ten thousand.
 */
@Entity
@Table(name = "runtime_measurement")
public class RuntimeMeasurement extends AbstractCreatedEntity {

    @Column(name = "repository_id", nullable = false, updatable = false)
    private UUID repositoryId;

    @Column(name = "analysis_run_id", nullable = false, updatable = false)
    private UUID analysisRunId;

    @Column(name = "artifact_id", updatable = false)
    private UUID artifactId;

    @Column(name = "metric_key", nullable = false, length = 120, updatable = false)
    private String metricKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_kind", nullable = false, length = 24, updatable = false)
    private MetricKind metricKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregation", nullable = false, length = 24, updatable = false)
    private MeasurementAggregation aggregation;

    @Column(name = "metric_value", nullable = false, updatable = false)
    private double value;

    @Column(name = "unit", nullable = false, length = 24, updatable = false)
    private String unit;

    @Column(name = "sample_count", nullable = false, updatable = false)
    private int sampleCount;

    @Column(name = "scope", length = 300, updatable = false)
    private String scope;

    /** Metric-specific detail as JSON: shares, causes, threshold caveats. */
    @Column(name = "attributes_json", length = 2000, updatable = false)
    private String attributesJson;

    protected RuntimeMeasurement() {
    }

    public RuntimeMeasurement(UUID repositoryId, UUID analysisRunId, UUID artifactId, MeasurementDraft draft,
                              String attributesJson) {
        this.repositoryId = repositoryId;
        this.analysisRunId = analysisRunId;
        this.artifactId = artifactId;
        this.metricKey = draft.metricKey();
        this.metricKind = draft.kind();
        this.aggregation = draft.aggregation();
        this.value = draft.value();
        this.unit = draft.unit();
        this.sampleCount = draft.sampleCount();
        this.scope = draft.scope();
        this.attributesJson = attributesJson;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public MetricKind getMetricKind() {
        return metricKind;
    }

    public MeasurementAggregation getAggregation() {
        return aggregation;
    }

    public double getValue() {
        return value;
    }

    public String getUnit() {
        return unit;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public String getScope() {
        return scope;
    }

    public String getAttributesJson() {
        return attributesJson;
    }
}
