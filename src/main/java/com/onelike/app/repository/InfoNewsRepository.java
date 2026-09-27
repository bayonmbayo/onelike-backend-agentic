package com.onelike.app.repository;

import com.onelike.app.model.InfoNews;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Repository
public interface InfoNewsRepository extends JpaRepository<InfoNews, Long> {
    InfoNews findByUrl(String url);

    // Cleanup: rows no Pin references any more (pin.info_news_id is a FK, so
    // this must run after the unpinned Pin rows have been deleted).
    @Modifying
    @Transactional
    @Query("DELETE FROM InfoNews n WHERE (n.savedat IS NULL OR n.savedat < :cutoff) "
         + "AND NOT EXISTS (SELECT 1 FROM Pin p WHERE p.infoNews = n)")
    int deleteOrphansOlderThan(@Param("cutoff") Date cutoff);
}
