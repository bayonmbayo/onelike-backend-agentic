package com.onelike.app.repository;

import com.onelike.app.model.GDPRData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GDPRDataRepository extends JpaRepository<GDPRData, Long> {

}
