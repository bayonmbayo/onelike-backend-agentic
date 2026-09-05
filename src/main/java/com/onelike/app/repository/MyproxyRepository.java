package com.onelike.app.repository;

import com.onelike.app.model.Breach;
import com.onelike.app.model.Myproxy;
import com.onelike.app.model.News;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MyproxyRepository extends JpaRepository<Myproxy, Long> {
    @Query("SELECT p FROM Myproxy p WHERE p.ip = :ip and p.port = :port")
    Myproxy findByIpAndPort(@Param("ip") String ip, @Param("port")Integer port);

    @Query("SELECT p FROM Myproxy p WHERE p.active = :active")
    List<Myproxy> findByActive(@Param("active") Boolean active);
}
