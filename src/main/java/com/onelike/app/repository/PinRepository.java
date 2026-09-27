package com.onelike.app.repository;

import com.onelike.app.model.InfoNews;
import com.onelike.app.model.Pin;
import com.onelike.app.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Repository
public interface PinRepository extends JpaRepository<Pin, Long> {
    @Query("SELECT p FROM Pin p WHERE p.user = :user AND p.infoNews = :infoNews")
    Pin getPin(@Param("user") User user, @Param("infoNews") InfoNews infoNews);

    // Ordered most-recently-pinned first - pinnedat is updated on every
    // pin/unpin, so this reflects the last time this article was (re-)saved,
    // not when the Pin row was originally created.
    @Query("SELECT p FROM Pin p WHERE p.pinned = :pinned AND p.user = :user ORDER BY p.pinnedat DESC")
    List<Pin> getMyPinned(@Param("pinned") Boolean pinned, @Param("user") User user);

    // Bulk, not per-article: one query for every article's pin count, so the
    // frontend can fetch this once instead of one request per rendered item
    // (the same N+1 mistake fixed earlier for isSaved()).
    @Query("SELECT n.url AS url, COUNT(p) AS count FROM Pin p JOIN p.infoNews n WHERE p.pinned = true GROUP BY n.url")
    List<UrlPinCount> getPinCountsByUrl();

    // Cleanup: unpinned rows are dead weight once they are older than the
    // cutoff (pinnedat is bumped on every pin/unpin, so a recent unpin is kept).
    @Modifying
    @Transactional
    @Query("DELETE FROM Pin p WHERE p.pinned = false AND (p.pinnedat IS NULL OR p.pinnedat < :cutoff)")
    int deleteUnpinnedOlderThan(@Param("cutoff") Date cutoff);
}
