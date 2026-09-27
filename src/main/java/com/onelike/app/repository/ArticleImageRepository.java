package com.onelike.app.repository;

import com.onelike.app.model.ArticleImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ArticleImageRepository extends JpaRepository<ArticleImage, Long> {

    /** One query for a whole feed's worth of pages, rather than one per article. */
    List<ArticleImage> findByUrlHashIn(Collection<String> urlHashes);
}
