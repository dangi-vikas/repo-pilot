package dev.repopilot.codeindex.persistence;

import dev.repopilot.codeindex.model.MappingType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JavaMappingRepository extends JpaRepository<JavaMapping, UUID> {

    List<JavaMapping> findByRevisionIdOrderByMappingTypeAscPathAsc(UUID revisionId);

    List<JavaMapping> findByRevisionIdAndMappingType(UUID revisionId, MappingType mappingType);

    Page<JavaMapping> findByRevisionIdAndMappingType(UUID revisionId, MappingType mappingType, Pageable pageable);

    long countByRevisionId(UUID revisionId);

    long countByRevisionIdAndMappingType(UUID revisionId, MappingType mappingType);

    @Query("SELECT m FROM JavaMapping m WHERE m.revisionId = :revisionId AND m.path IS NOT NULL "
            + "ORDER BY m.path ASC")
    List<JavaMapping> findEndpoints(@Param("revisionId") UUID revisionId);
}
