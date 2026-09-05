package com.onelike.app.service;

import com.onelike.app.repository.FilterNewsRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CacheLoader {

    @Autowired
    NewsService newsService;
    @Autowired
    CacheService cacheService;
    @Autowired
    QuoteService quoteService;

    // Preload cache when the application starts
    @PostConstruct
    // @CachePut(value = "users")
    public void preloadCache() throws Exception {
        System.out.println("Loading data into cache...");

        newsService.retrieveEnglishNews();
        newsService.retrieveArabicNews();
        newsService.retrieveChineseNews();
        newsService.retrieveDeutschNews();
        newsService.retrieveSpanishNews();
        newsService.retrieveFrancaisNews();
        newsService.retrieveGreekNews();
        newsService.retrieveHindiNews();
        newsService.retrieveIndonesianNews();
        newsService.retrieveItalianNews();
        newsService.retrieveJapaneseNews();
        newsService.retrieveDutchNews();
        newsService.retrievePortugueseNews();
        newsService.retrieveRussianNews();
        newsService.retrieveTurkishNews();
        newsService.allFilterNews();
        quoteService.getAllQuotes();
    }

    @EventListener(ApplicationReadyEvent.class)
    // @CachePut(value = "users")
    public void loadCacheOnStartup() throws Exception {
        System.out.println("Loading data into cache on startup...");

        newsService.retrieveEnglishNews();
        newsService.retrieveArabicNews();
        newsService.retrieveChineseNews();
        newsService.retrieveDeutschNews();
        newsService.retrieveSpanishNews();
        newsService.retrieveFrancaisNews();
        newsService.retrieveGreekNews();
        newsService.retrieveHindiNews();
        newsService.retrieveIndonesianNews();
        newsService.retrieveItalianNews();
        newsService.retrieveJapaneseNews();
        newsService.retrieveDutchNews();
        newsService.retrievePortugueseNews();
        newsService.retrieveRussianNews();
        newsService.retrieveTurkishNews();
        newsService.allFilterNews();
        quoteService.getAllQuotes();
    }

    @Scheduled(fixedRate = 600000)  // Every 10 minutes
    public void refreshUsersCache() throws Exception {
        cacheService.evictCache();  // Clear old cache

        newsService.retrieveEnglishNews();
        newsService.retrieveArabicNews();
        newsService.retrieveChineseNews();
        newsService.retrieveDeutschNews();
        newsService.retrieveSpanishNews();
        newsService.retrieveFrancaisNews();
        newsService.retrieveGreekNews();
        newsService.retrieveHindiNews();
        newsService.retrieveIndonesianNews();
        newsService.retrieveItalianNews();
        newsService.retrieveJapaneseNews();
        newsService.retrieveDutchNews();
        newsService.retrievePortugueseNews();
        newsService.retrieveRussianNews();
        newsService.retrieveTurkishNews();
        newsService.allFilterNews();
        quoteService.getAllQuotes();

        System.out.println("Cache refreshed!");
    }
}
