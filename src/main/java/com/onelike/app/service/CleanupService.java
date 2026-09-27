package com.onelike.app.service;

import com.onelike.app.repository.ArticleImageRepository;
import com.onelike.app.repository.GoogleNewsLinkRepository;
import com.onelike.app.repository.InfoNewsRepository;
import com.onelike.app.repository.PinRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Periodic purge of rows that are older than {@code onelike.cleanup.min-age-ms}
 * (default 72h) and no longer needed: unpinned Pin rows, InfoNews rows no Pin
 * references, and GoogleNewsLink / ArticleImage cache rows whose article is not
 * behind any still-pinned InfoNews.
 *
 * The four deletes must run in this order: pin.info_news_id is a foreign key
 * to info_news.id, so unpinned Pin rows have to go before the InfoNews rows
 * they point at become orphans; the two cache tables are checked against the
 * pinned set that remains after that.
 *
 * Each step is its own transaction, declared with @Transactional on the
 * repository methods rather than here: purgeUnused() is invoked by the
 * scheduler, so an annotation on this class would work, but the per-step
 * try/catch below would not be able to keep going after one failed step if
 * they all shared a single transaction that was already marked rollback-only.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "onelike.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class CleanupService {
    @Autowired
    PinRepository pinRepository;
    @Autowired
    InfoNewsRepository infoNewsRepository;
    @Autowired
    GoogleNewsLinkRepository googleNewsLinkRepository;
    @Autowired
    ArticleImageRepository articleImageRepository;

    @Value("${onelike.cleanup.min-age-ms:259200000}")
    private long minAgeMs;

    @Scheduled(fixedDelayString = "${onelike.cleanup.fixed-delay-ms:259200000}",
               initialDelayString = "${onelike.cleanup.initial-delay-ms:600000}")
    public void purgeUnused() {
        Date cutoff = new Date(System.currentTimeMillis() - minAgeMs);

        int pins = 0;
        int infoNews = 0;
        int links = 0;
        int images = 0;

        try {
            pins = pinRepository.deleteUnpinnedOlderThan(cutoff);
        } catch (Exception e) {
            log.warn("Cleanup: deleting unpinned pin rows failed", e);
        }
        try {
            infoNews = infoNewsRepository.deleteOrphansOlderThan(cutoff);
        } catch (Exception e) {
            log.warn("Cleanup: deleting orphaned info_news rows failed", e);
        }
        try {
            links = googleNewsLinkRepository.deleteUnusedOlderThan(cutoff);
        } catch (Exception e) {
            log.warn("Cleanup: deleting unused google_news_link rows failed", e);
        }
        try {
            images = articleImageRepository.deleteUnusedOlderThan(cutoff);
        } catch (Exception e) {
            log.warn("Cleanup: deleting unused article_image rows failed", e);
        }

        log.info("Cleanup: removed {} unpinned pin, {} orphaned info_news, {} google_news_link, {} article_image rows (unused, older than {}h)",
                pins, infoNews, links, images, minAgeMs / 3600000);
    }
}
