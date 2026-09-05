package com.onelike.app.repository;

import com.onelike.app.model.Breach;
import com.onelike.app.model.Search;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BreachRepository extends JpaRepository<Breach, Long>  {
    @Query("SELECT b FROM Breach b WHERE b.account = :account")
    Breach findByAccount(@Param("account") String account);
}
