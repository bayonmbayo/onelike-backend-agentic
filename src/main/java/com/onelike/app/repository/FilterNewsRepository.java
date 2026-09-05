package com.onelike.app.repository;

import com.onelike.app.model.FilterNews;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FilterNewsRepository extends JpaRepository<FilterNews, Long> {
    
    @Query("SELECT fn FROM FilterNews fn WHERE fn.active = :active")
    List<FilterNews> getActiveFilterNews(@Param("active") Boolean active);
}
