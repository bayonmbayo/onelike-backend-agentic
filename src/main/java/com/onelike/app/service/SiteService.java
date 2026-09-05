package com.onelike.app.service;

import com.onelike.app.model.Site;
import com.onelike.app.repository.SiteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SiteService {

    @Autowired
    SiteRepository siteRepository;

    @Transactional
    public void deleteSiteList(List<Site> siteList) {
        siteRepository.deleteAllInBatch(siteList);
    }
}
