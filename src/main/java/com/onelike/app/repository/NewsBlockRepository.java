package com.onelike.app.repository;

import com.onelike.app.model.News;
import com.onelike.app.model.NewsBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NewsBlockRepository extends JpaRepository<NewsBlock, Long> {
    @Query("SELECT n FROM NewsBlock n WHERE n.socialmedia = :socialmedia and n.language = :language")
    NewsBlock findBySocialMediaAndLanguage(@Param("socialmedia") String socialmedia, @Param("language")String language);
}
