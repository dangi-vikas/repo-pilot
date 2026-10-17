package dev.repopilot.workspace;

import dev.repopilot.shared.persistence.AbstractAuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A workspace groups repositories and is the boundary for every query in the API.
 *
 * <p>Workspaces exist from the first version even though a local installation
 * typically has exactly one. The isolation boundary is cheap to add now and
 * expensive to retrofit later: every repository, job, finding and investigation
 * already resolves through a workspace, so a future multi-user or
 * multi-project deployment does not require a data migration across the whole
 * schema.
 */
@Entity
@Table(name = "workspace")
public class Workspace extends AbstractAuditedEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** URL-safe, unique, immutable key. Generated from the name. */
    @Column(name = "slug", nullable = false, length = 200)
    private String slug;

    @Column(name = "description", length = 2000)
    private String description;

    protected Workspace() {
        // Required by JPA.
    }

    public Workspace(String name, String slug, String description) {
        this.name = name;
        this.slug = slug;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
