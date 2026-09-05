package com.onelike.app.service;

import com.onelike.app.model.FilterNews;
import com.onelike.app.model.News;
import com.onelike.app.model.Search;
import com.onelike.app.repository.FilterNewsRepository;
import com.onelike.app.repository.NewsRepository;
import com.onelike.app.repository.SearchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NewsService {

    @Autowired
    NewsRepository repository;

    @Autowired
    FilterNewsRepository filterNewsRepository;
    @Autowired
    CacheService cacheService;

    @Value("${onelike.redis.enabled}")
    private Boolean onRedis;

    private final Map<String, News> fallbackCache = new ConcurrentHashMap<>();


    public void save(News news, String language) throws Exception {
        switch (language){
            case "Arabic" -> saveAR(news);
            case "Chinese" -> saveCH(news);
            case "Deutsch" -> saveDE(news);
            case "Spanish" -> saveES(news);
            case "Francais" -> saveFR(news);
            case "Greek" -> saveGR(news);
            case "Hindi" -> saveHI(news);
            case "Indonesian" -> saveINDO(news);
            case "Italian" -> saveIT(news);
            case "Japanese" -> saveJP(news);
            case "Dutch" -> saveNL(news);
            case "Portuguese" -> savePT(news);
            case "Russian" -> saveRU(news);
            case "Turkish" -> saveTR(news);
            default -> saveEN(news); // For English
        }
    }

    @Transactional
    public News saveEntireFeed(News news) throws Exception {
        try {
            repository.save(news);
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news")
    public News saveEN(News news) throws Exception {
        try {
            cacheService.evictCacheEN();
            repository.save(news);
            retrieveEnglishNews();
            // Redis
            // fallbackCache.put(news.getLanguage(), news);
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-ar")
    public News saveAR(News news) throws Exception {
        try {
            cacheService.evictCacheAR();
            repository.save(news);
            retrieveArabicNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-ch")
    public News saveCH(News news) throws Exception {
        try {
            cacheService.evictCacheCH();
            repository.save(news);
            retrieveChineseNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-de")
    public News saveDE(News news) throws Exception {
        try {
            cacheService.evictCacheDE();
            repository.save(news);
            retrieveDeutschNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-es")
    public News saveES(News news) throws Exception {
        try {
            cacheService.evictCacheES();
            repository.save(news);
            retrieveSpanishNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-fr")
    public News saveFR(News news) throws Exception {
        try {
            cacheService.evictCacheFR();
            repository.save(news);
            retrieveFrancaisNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-gr")
    public News saveGR(News news) throws Exception {
        try {
            cacheService.evictCacheGR();
            repository.save(news);
            retrieveGreekNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-hi")
    public News saveHI(News news) throws Exception {
        try {
            cacheService.evictCacheHI();
            repository.save(news);
            retrieveHindiNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-indo")
    public News saveINDO(News news) throws Exception {
        try {
            cacheService.evictCacheINDO();
            repository.save(news);
            retrieveIndonesianNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-it")
    public News saveIT(News news) throws Exception {
        try {
            cacheService.evictCacheIT();
            repository.save(news);
            retrieveItalianNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-jp")
    public News saveJP(News news) throws Exception {
        try {
            cacheService.evictCacheJP();
            repository.save(news);
            retrieveJapaneseNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-nl")
    public News saveNL(News news) throws Exception {
        try {
            cacheService.evictCacheNL();
            repository.save(news);
            retrieveDutchNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-pt")
    public News savePT(News news) throws Exception {
        try {
            cacheService.evictCachePT();
            repository.save(news);
            retrievePortugueseNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-ru")
    public News saveRU(News news) throws Exception {
        try {
            cacheService.evictCacheRU();
            repository.save(news);
            retrieveRussianNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    @CachePut(value = "news-tr")
    public News saveTR(News news) throws Exception {
        try {
            cacheService.evictCacheTR();
            repository.save(news);
            retrieveTurkishNews();
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    public News findByLanguage(String language) throws Exception {
        return repository.findByLanguage(language);
    }

    @Transactional
    public News findByProperty(Integer property) throws Exception {
        return repository.findByProperty(property);
    }

    @Transactional
    public News findByPropertyAndLanguage(Integer property, String language) throws Exception {
        return repository.findByPropertyAndLanguage(property, language);
    }

    // Use FallbackCache if RedisEnabled
    public News useCache(String language) throws Exception{
        if(!onRedis){
            // First, check the fallback in-memory cache
//            if (fallbackCache.containsKey(getCacheValue(language))) {
//                System.out.println("Already in Cache");
//                News cached = fallbackCache.get(getCacheValue(language));
//                System.out.println("Cached : " + cached);
//                if(cached != null){
//                    return cached;
//                }
//            }

            // Fallback cache miss → load from DB
            System.out.println("Loading from DB");
            return repository.findByPropertyAndLanguage(1, language);
        }

        // FallbackCache
        try {
            // Try DB
            News news = repository.findByPropertyAndLanguage(1, language);

            if (news != null) {
                fallbackCache.put(news.getLanguage(), news); // update fallback cache
                return news;
            } else {
                // Not found in DB
                return fallbackCache.getOrDefault(language, getPlaceholderNews(99999L));
            }

        } catch (DataAccessException ex) {
            // DB connection failed — use fallback
            return fallbackCache.getOrDefault(language, getPlaceholderNews(99999L));
        }
    }

    public List<FilterNews> getAllFilterNews(){
        return allFilterNews();
    }

    @Cacheable(value = "filter_news")
    public List<FilterNews> allFilterNews(){
        return filterNewsRepository.getActiveFilterNews(true);
    }

    @Cacheable(value = "news")
    public News retrieveEnglishNews() throws Exception {
        // System.out.println("Using DB Now");
        return useCache("English");
    }

    @Cacheable(value = "news-ar")
    public News retrieveArabicNews() throws Exception {
        return useCache("Arabic");
    }

    @Cacheable(value = "news-ch")
    public News retrieveChineseNews() throws Exception {
        return useCache("Chinese");
    }

    @Cacheable(value = "news-de")
    public News retrieveDeutschNews() throws Exception {
        return useCache("Deutsch");
    }

    @Cacheable(value = "news-es")
    public News retrieveSpanishNews() throws Exception {
        return useCache("Spanish");
    }

    @Cacheable(value = "news-fr")
    public News retrieveFrancaisNews() throws Exception {
        return useCache("Francais");
    }

    @Cacheable(value = "news-gr")
    public News retrieveGreekNews() throws Exception {
        return useCache("Greek");
    }

    @Cacheable(value = "news-hi")
    public News retrieveHindiNews() throws Exception {
        return useCache("Hindi");
    }

    @Cacheable(value = "news-indo")
    public News retrieveIndonesianNews() throws Exception {
        return useCache("Indonesian");
    }

    @Cacheable(value = "news-it")
    public News retrieveItalianNews() throws Exception {
        return useCache("Italian");
    }

    @Cacheable(value = "news-jp")
    public News retrieveJapaneseNews() throws Exception {
        return useCache("Japanese");
    }

    @Cacheable(value = "news-nl")
    public News retrieveDutchNews() throws Exception {
        return useCache("Dutch");
    }

    @Cacheable(value = "news-pt")
    public News retrievePortugueseNews() throws Exception {
        return useCache("Portuguese");
    }

    @Cacheable(value = "news-ru")
    public News retrieveRussianNews() throws Exception {
        return useCache("Russian");
    }

    @Cacheable(value = "news-tr")
    public News retrieveTurkishNews() throws Exception {
        return useCache("Turkish");
    }

    private News getPlaceholderNews(Long id) {
        News fallback = new News();
        fallback.setId(id);
        fallback.setFeed("No news available at the moment.");
        return fallback;
    }

    private String getCacheValue(String language){
        switch (language){
            case "English" -> {return "news";}
            case "Arabic" -> {return "news-ar";}
            case "Chinese" -> {return "news-ch";}
            case "Deutsch" -> {return "news-de";}
            case "Spanish" -> {return "news-es";}
            case "Francais" -> {return "news-fr";}
            case "Greek" -> {return "news-gr";}
            case "Hindi" -> {return "news-hi";}
            case "Indonesian" -> {return "news-indo";}
            case "Italian" -> {return "news-it";}
            case "Japanese" -> {return "news-jp";}
            case "Dutch" -> {return "news-nl";}
            case "Portuguese" -> {return "news-pt";}
            case "Russian" -> {return "news-ru";}
            case "Turkish" -> {return "news-tr";}
        }

        return null;
    }
}
