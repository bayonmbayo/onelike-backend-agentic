package com.onelike.app.repository;

import com.onelike.app.model.GoogleNewsLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Date;
import java.util.List;

@Repository
public interface GoogleNewsLinkRepository extends JpaRepository<GoogleNewsLink, Long> {

    /** One query for a whole feed's worth of ids, rather than one per article. */
    List<GoogleNewsLink> findByArticleHashIn(Collection<String> articleHashes);

    boolean existsByArticleHash(String articleHash);

    /**
     * Cleanup: old mappings whose publisher URL is not behind any still-pinned
     * article. Pinned articles keep theirs so the saved list resolves without
     * going back through the proxy.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM GoogleNewsLink g "
         + "WHERE (g.created IS NULL OR g.created < :cutoff) "
         + "AND NOT EXISTS (SELECT 1 FROM Pin p WHERE p.pinned = true AND p.infoNews.url = g.publisherUrl)")
    int deleteUnusedOlderThan(@Param("cutoff") Date cutoff);
}
