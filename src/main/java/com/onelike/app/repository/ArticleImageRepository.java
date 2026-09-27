package com.onelike.app.repository;

import com.onelike.app.model.ArticleImage;
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
public interface ArticleImageRepository extends JpaRepository<ArticleImage, Long> {

    /** One query for a whole feed's worth of pages, rather than one per article. */
    List<ArticleImage> findByUrlHashIn(Collection<String> urlHashes);

    /**
     * Cleanup: old image lookups whose page is not a still-pinned article.
     * Native because url_hash is SHA-256 hex of the page URL and JPQL has no
     * SHA2(); MySQL's SHA2(..., 256) yields the same lowercase hex as the Java
     * side, so the hashes compare directly.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM article_image "
         + "WHERE checked < :cutoff "
         + "AND url_hash NOT IN ("
         + "  SELECT SHA2(n.url, 256) FROM pin p JOIN info_news n ON n.id = p.info_news_id "
         + "  WHERE p.pinned = 1 AND n.url IS NOT NULL)",
           nativeQuery = true)
    int deleteUnusedOlderThan(@Param("cutoff") Date cutoff);
}
