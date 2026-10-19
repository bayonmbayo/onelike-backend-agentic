package com.onelike.app.configuration;

import com.onelike.app.model.News;
import com.onelike.app.service.NewsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.Date;

/**
 * Creates the two News rows every language needs, if they are not there yet.
 *
 * Without them the first successful feed run calls newsService.save(null, ...)
 * and dies with "Entity must not be null" -- which is what German hit, because
 * its rows had never been seeded.
 *
 * Rows are seeded with EMPTY feeds rather than null content:
 *   - getNews() returns news.getFeed() straight to the client, so a null feed
 *     means a 200 with an empty body.
 *   - performSearchNews() parses getAllFeedWithoutCategory(), so null is an NPE.
 *
 * The two rows hold different shapes, so they get different empty values.
 * saveNews() skips writing a row whose refresh has no news (no card with news
 * for property 1, an empty "news" list for property 2), so seeded rows are
 * left alone until there is real content.
 *
 * Runs in @PostConstruct rather than on ApplicationReadyEvent. That event fires
 * AFTER "Started OnelikeBackendApplication" and after the scheduler has already
 * launched the feed jobs -- your logs showed jobs running at 08:28:24,382 and
 * startup completing at 08:28:24,392. @PostConstruct runs during bean creation,
 * so the seeding happens before any job can touch the table, and its log line
 * appears mid-startup where it is actually visible.
 *
 * Idempotent: safe on every boot, and safe to run against a populated table.
 */
@Slf4j
@Component
public class NewsTableSeeder {

    /** property 1 holds allSocialNews -> {"socialmedias": [...]} */
    private static final String EMPTY_CATEGORISED_FEED = "{\"socialmedias\" : []}";

    /** property 2 holds allSocialNewsWithoutCategory -> {"news": [...]} */
    private static final String EMPTY_FLAT_FEED = "{\"news\" : []}";

    @Autowired
    NewsService newsService;

    /**
     * Must match the language strings EXACTLY as each SearchControllerXX passes
     * them to saveNews(), because findByPropertyAndLanguage does an exact match.
     * Note the inconsistency already in the data: German is "Deutsch" and French
     * is "Francais", while the rest use English names. Seeding "German" here
     * would create a second, orphaned row next to the real "Deutsch" one.
     */
    @Value("${onelike.news.languages:"
            + "English,Deutsch,Spanish,Francais,Greek,Hindi,Indonesian,Italian,"
            + "Japanese,Dutch,Portuguese,Russian,Turkish,Arabic,Chinese}")
    private String[] languages;

    @PostConstruct
    public void seed() {
        if (languages == null || languages.length == 0) {
            log.warn("No languages configured for News seeding");
            return;
        }

        int created = 0;
        int checked = 0;
        for (String raw : languages) {
            String language = raw == null ? "" : raw.trim();
            if (!language.isEmpty()) {
                created += seedLanguage(language);
                checked++;
            }
        }

        // Always log, even at zero -- otherwise a silent no-op is
        // indistinguishable from the seeder never having run at all.
        log.info("News seeding done: {} language(s) checked, {} row(s) created", checked, created);
    }

    private int seedLanguage(String language) {
        int created = 0;

        try {
            if (newsService.findByPropertyAndLanguage(1, language) == null) {
                News categorised = new News();
                categorised.setLanguage(language);
                categorised.setProperty(1);
                categorised.setFeed(EMPTY_CATEGORISED_FEED);
                categorised.setUpdated(new Date());
                newsService.save(categorised, language);
                log.info("Seeded News row for {} (property 1)", language);
                created++;
            }
        } catch (Exception e) {
            log.warn("Could not seed News row for {} (property 1): {}", language, e.getMessage());
        }

        try {
            if (newsService.findByPropertyAndLanguage(2, language) == null) {
                News flat = new News();
                flat.setLanguage(language);
                flat.setProperty(2);
                flat.setAllFeedWithoutCategory(EMPTY_FLAT_FEED);
                flat.setUpdated(new Date());
                newsService.saveEntireFeed(flat);
                log.info("Seeded News row for {} (property 2)", language);
                created++;
            }
        } catch (Exception e) {
            log.warn("Could not seed News row for {} (property 2): {}", language, e.getMessage());
        }

        return created;
    }
}