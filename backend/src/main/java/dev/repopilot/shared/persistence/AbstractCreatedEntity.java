package dev.repopilot.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Transient;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity and creation timestamp for append-only rows.
 *
 * <p>Deliberately narrower than {@link AbstractAuditedEntity}: derived facts such as
 * source files, symbols, dependency edges and findings are written once per revision
 * and never updated. Giving them an optimistic-lock version column and an
 * {@code updated_at} timestamp would be dead weight on the largest tables in the
 * schema and would imply an update path that does not exist.
 *
 * <p>The identifier is assigned by Hibernate at persist time and is never
 * pre-populated in a field initialiser, for the same reason documented on
 * {@link AbstractAuditedEntity}: a non-null id makes a new entity look detached.
 *
 * <p>Like {@link AbstractAuditedEntity} this implements {@link Persistable} so that
 * newness is declared explicitly rather than inferred from the identifier. Here the
 * inference happens to be correct today, but the derived rows are written in the
 * indexer's hot loop and often immediately referenced (a symbol points at its source
 * file), so it is worth making the {@code persist}-not-{@code merge} behaviour a
 * stated contract instead of an accident of the id being null.
 */
@MappedSuperclass
public abstract class AbstractCreatedEntity implements Persistable<UUID> {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean newEntity = true;

    @PrePersist
    void markCreated() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.newEntity = false;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AbstractCreatedEntity that)) {
            return false;
        }
        return getClass() == that.getClass() && id != null && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), id);
    }
}
