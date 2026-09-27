package com.onelike.app.repository;

import com.onelike.app.model.InfoNews;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InfoNewsRepository extends JpaRepository<InfoNews, Long> {
    InfoNews findByUrl(String url);
}
