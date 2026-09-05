package com.onelike.app.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

@Service
public class CacheService {

    @CacheEvict(value = {
            "news",
            "news-ar",
            "news-ch",
            "news-de",
            "news-es",
            "news-fr",
            "news-gr",
            "news-hi",
            "news-indo",
            "news-it",
            "news-jp",
            "news-nl",
            "news-pt",
            "news-ru",
            "news-tr",
            "filter_news",
            "quotes"
    }, allEntries = true)
    public void evictCache() {
        System.out.println("Cache cleared!");
    }


    @CacheEvict(value = {
            "news"
    }, allEntries = true)
    public void evictCacheEN() {
        System.out.println("EN Cache cleared!");
    }

    @CacheEvict(value = {
            "news-ar"
    }, allEntries = true)
    public void evictCacheAR() {
        System.out.println("AR Cache cleared!");
    }

    @CacheEvict(value = {
            "news-ch"
    }, allEntries = true)
    public void evictCacheCH() {
        System.out.println("CH Cache cleared!");
    }

    @CacheEvict(value = {
            "news-de"
    }, allEntries = true)
    public void evictCacheDE() {
        System.out.println("DE Cache cleared!");
    }

    @CacheEvict(value = {
            "news-es"
    }, allEntries = true)
    public void evictCacheES() {
        System.out.println("ES Cache cleared!");
    }

    @CacheEvict(value = {
            "news-fr"
    }, allEntries = true)
    public void evictCacheFR() {
        System.out.println("FR Cache cleared!");
    }

    @CacheEvict(value = {
            "news-gr"
    }, allEntries = true)
    public void evictCacheGR() {
        System.out.println("GR Cache cleared!");
    }

    @CacheEvict(value = {
            "news-hi"
    }, allEntries = true)
    public void evictCacheHI() {
        System.out.println("HI Cache cleared!");
    }

    @CacheEvict(value = {
            "news-indo"
    }, allEntries = true)
    public void evictCacheINDO() {
        System.out.println("INDO Cache cleared!");
    }

    @CacheEvict(value = {
            "news-it"
    }, allEntries = true)
    public void evictCacheIT() {
        System.out.println("IT Cache cleared!");
    }

    @CacheEvict(value = {
            "news-jp"
    }, allEntries = true)
    public void evictCacheJP() {
        System.out.println("JP Cache cleared!");
    }

    @CacheEvict(value = {
            "news-nl"
    }, allEntries = true)
    public void evictCacheNL() {
        System.out.println("NL Cache cleared!");
    }

    @CacheEvict(value = {
            "news-pt"
    }, allEntries = true)
    public void evictCachePT() {
        System.out.println("PT Cache cleared!");
    }

    @CacheEvict(value = {
            "news-ru"
    }, allEntries = true)
    public void evictCacheRU() {
        System.out.println("RU Cache cleared!");
    }

    @CacheEvict(value = {
            "news-tr"
    }, allEntries = true)
    public void evictCacheTR() {
        System.out.println("TR Cache cleared!");
    }
}
