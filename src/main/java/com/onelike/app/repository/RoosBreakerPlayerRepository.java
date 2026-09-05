package com.onelike.app.repository;

import com.onelike.app.model.Quote;
import com.onelike.app.model.RoosBreakerPlayer;
import com.onelike.app.model.Search;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RoosBreakerPlayerRepository extends JpaRepository<RoosBreakerPlayer, Long> {
    @Query("SELECT r FROM RoosBreakerPlayer r WHERE r.username= :username")
    RoosBreakerPlayer findByUsername(@Param("username") String username);
}
