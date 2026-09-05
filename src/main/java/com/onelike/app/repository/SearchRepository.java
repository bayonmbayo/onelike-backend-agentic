package com.onelike.app.repository;


import com.onelike.app.model.Search;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SearchRepository extends JpaRepository<Search, Long>{
    @Query("SELECT s FROM Search s WHERE s.searchtext = :searchtext")
    Search findBySearchText(@Param("searchtext") String searchtext);
}
