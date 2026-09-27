package com.onelike.app.repository;

import com.onelike.app.model.GoogleNewsLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface GoogleNewsLinkRepository extends JpaRepository<GoogleNewsLink, Long> {

    /** One query for a whole feed's worth of ids, rather than one per article. */
    List<GoogleNewsLink> findByArticleHashIn(Collection<String> articleHashes);

    boolean existsByArticleHash(String articleHash);
}
