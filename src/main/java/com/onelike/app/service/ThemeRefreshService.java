package com.onelike.app.service;

import com.onelike.app.model.NewsBlock;
import com.onelike.app.repository.ThemeKey;
import com.onelike.app.repository.UserNewsContainerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the custom theme cards from going stale. A theme's NewsBlock is only
 * ever written by a fetch, and the create path short-circuits on the second
 * create of the same theme (findByUserAndKindAndNamekeyAndLanguage returns the
 * existing row before it ever gets to the freshness check), so without this job
 * a theme's articles are frozen at the minute the first user created it.
 *
 * Its own class, not a method on UserNewsContainerService, because
 * {@literal @}ConditionalOnProperty is all-or-nothing on a bean:
 * UserNewsContainerService has to exist unconditionally to serve the four
 * /service/* container endpoints, so the switch for the scheduled work needs a
 * bean of its own.
 *
 * Deliberately NOT {@literal @}Transactional, for the same reason as
 * CleanupService: a class-level transaction would be marked rollback-only by
 * the first theme that failed and poison the rest of the run. The only writes
 * happen inside articlesOf via newsBlockService.save, which carries its own.
 *
 * fixedDelayString, not fixedRate: the delay is measured from completion, so a
 * tick that spends minutes waiting on dead proxies can never overlap itself or
 * stack up behind the previous one.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "onelike.containers.refresh.enabled", havingValue = "true", matchIfMissing = true)
public class ThemeRefreshService {

    @Autowired
    UserNewsContainerRepository containerRepository;

    @Autowired
    NewsBlockService newsBlockService;

    @Autowired
    UserNewsContainerService containerService;

    /**
     * How many themes one tick may fetch. A theme costs a proxy round trip, so
     * this is the proxy budget this job is allowed to spend per tick; the rest
     * wait for the next one, oldest first.
     */
    @Value("${onelike.containers.refresh.max-per-tick:5}")
    private int maxPerTick;

    @Scheduled(fixedDelayString = "${onelike.containers.refresh.fixed-delay-ms:1800000}",
               initialDelayString = "${onelike.containers.refresh.initial-delay-ms:300000}")
    public void refreshThemes() {
        List<ThemeKey> keys;
        try {
            keys = containerRepository.findDistinctThemeKeys(UserNewsContainerService.KIND_THEME);
        } catch (Exception e) {
            log.warn("Theme refresh: listing the live themes failed, skipping this tick", e);
            return;
        }

        List<Candidate> candidates = normalize(keys);

        int fresh = 0;
        int failed = 0;
        List<Candidate> stale = new ArrayList<>();

        for (Candidate candidate : candidates) {
            try {
                NewsBlock block = newsBlockService
                        .findBySocialMediaAndLanguage(candidate.title, candidate.language);
                if (containerService.isFresh(block)) {
                    fresh++;
                    continue;
                }
                candidate.updated = block == null ? null : block.getUpdated();
                stale.add(candidate);
            } catch (Exception e) {
                failed++;
                log.warn("Theme refresh: reading the cached block for the theme "
                        + candidate.title + " " + candidate.language + " failed", e);
            }
        }

        // Oldest first, never-fetched first of all: a theme whose block is
        // missing is either brand new or the leftover of a failed create, and
        // it is the one showing an empty card right now.
        stale.sort(Comparator.comparing((Candidate candidate) -> candidate.updated,
                Comparator.nullsFirst(Comparator.<Date>naturalOrder())));

        int refreshed = 0;
        int budget = Math.min(maxPerTick, stale.size());

        for (int i = 0; i < budget; i++) {
            Candidate candidate = stale.get(i);
            try {
                boolean attempted = containerService.loadThemeContent(candidate.title, candidate.language);
                NewsBlock after = newsBlockService
                        .findBySocialMediaAndLanguage(candidate.title, candidate.language);

                if (hasMoved(candidate.updated, after)) {
                    refreshed++;
                } else {
                    failed++;
                    log.debug("Theme refresh: the theme " + candidate.title + " " + candidate.language
                            + " has no new content (fetch attempted: " + attempted + ")");
                }
            } catch (Exception e) {
                failed++;
                log.warn("Theme refresh: fetching the theme "
                        + candidate.title + " " + candidate.language + " failed", e);
            }
        }

        log.info("Theme refresh: {} live themes, {} fresh, {} refreshed, {} failed, {} deferred to a later tick",
                candidates.size(), fresh, refreshed, failed, stale.size() - budget);
    }

    /**
     * Whether this theme's card really has new content, which is the only thing
     * "refreshed" may be allowed to count.
     *
     * "A fetch was attempted" is NOT "a fetch succeeded": articlesOf swallows
     * its own failures and answers a JSONObject either way - no proxy, a dead
     * URL and a broken feed all return the block's EXISTING feed and save
     * nothing at all. So the boolean it hands back cannot distinguish a full
     * refresh from a total outage, and counting it as success made this job's
     * one summary line report "5 refreshed, 0 failed" for a tick in which
     * nothing was fetched.
     *
     * The block's own updated stamp is the honest signal, because only the save
     * inside articlesOf ever moves it. Read back through the same
     * NewsBlockService the staleness check above used: this is a different
     * question from isFresh ("did this row change"), not a second definition of
     * freshness.
     *
     * The no-block case is deliberate rather than incidental: a theme that has
     * never been fetched (the nullsFirst candidates) has a null "before", so a
     * first successful fetch reads null -> non-null and counts as refreshed,
     * while a failed one leaves the row absent and counts as failed. An
     * unchanged stamp counts as failed too - the comparison is strict - which is
     * what the "every proxy is down" tick really is.
     */
    private boolean hasMoved(Date before, NewsBlock after) {
        if (after == null || after.getUpdated() == null) {
            return false;
        }
        return before == null || after.getUpdated().after(before);
    }

    /**
     * Turns the raw DISTINCT rows into the keys the read path will actually
     * read: the title trimmed, a missing or blank language resolved to the same
     * DEFAULT_LANGUAGE that themeLanguage falls back to (a row written before
     * language was stored counts as English, not as "every language"), blank
     * titles dropped, and the result deduped - normalising can collapse two
     * rows into one key.
     */
    private List<Candidate> normalize(List<ThemeKey> keys) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ThemeKey key : keys) {
            if (key == null || key.getName() == null || key.getName().trim().isEmpty()) {
                continue;
            }
            String title = key.getName().trim();
            String language = key.getLanguage() == null || key.getLanguage().trim().isEmpty()
                    ? UserNewsContainerService.DEFAULT_LANGUAGE
                    : key.getLanguage().trim();

            if (seen.add(title + "|" + language)) {
                candidates.add(new Candidate(title, language));
            }
        }
        return candidates;
    }

    /** One theme to consider, plus how old its cached block turned out to be. */
    private static class Candidate {
        private final String title;
        private final String language;
        private Date updated;

        private Candidate(String title, String language) {
            this.title = title;
            this.language = language;
        }
    }
}
