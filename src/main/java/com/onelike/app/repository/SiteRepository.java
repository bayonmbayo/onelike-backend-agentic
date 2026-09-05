package com.onelike.app.repository;

import com.onelike.app.model.Breach;
import com.onelike.app.model.Site;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SiteRepository extends JpaRepository<Site, Long> {
}
