package com.onelike.app.repository;

import com.onelike.app.model.Breach;
import com.onelike.app.model.News;
import com.onelike.app.model.Search;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NewsRepository extends JpaRepository<News, Long> {
    @Query("SELECT n FROM News n WHERE n.language = :language")
    News findByLanguage(@Param("language") String language);

    @Query("SELECT n FROM News n WHERE n.property = :property")
    News findByProperty(@Param("property") Integer property);

    @Query("SELECT n FROM News n WHERE n.property = :property and n.language = :language")
    News findByPropertyAndLanguage(@Param("property") Integer property, @Param("language")String language);
}
