package com.onelike.app.util;

import com.onelike.app.model.ArticleImage;
import com.onelike.app.model.Myproxy;
import com.onelike.app.repository.ArticleImageRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DB-backed cache of publisher page URL -> preview image URL, in front of
 * {@link ImagePreview}.
 *
 * The preview job runs every 5 minutes per language x network, and before this
 * cache every article in every feed cost two proxy requests per cycle (page
 * fetch + image HEAD) -- the same articles over and over, which blew through the
 * proxy provider's budget. Now each article is scanned once: a positive result
 * is never re-scanned for as long as its row lives (an article's image does not
 * change), negative results are re-tried once a day in case the page was
 * temporarily unreachable. "Never re-scanned" is the cache's policy, not the
 * row's lifetime: CleanupService purges rows no pin depends on every 72 hours,
 * and a purged page is simply rescanned on next use - see ArticleImage's own
 * javadoc, which states it the same way.
 *
 * Static and reached through the same initStaticDao() pattern as
 * GoogleNewsUrlDecoder, because the caller (giveMeArrayOfLinks) is static and
 * shared by every language controller.
 *
 * Never throws and never returns null. If the DB is unavailable it degrades to
 * scanning everything, exactly as before the cache existed.
 */
@Slf4j
@Component
public class ArticleImageCache {

    @Autowired
    ArticleImageRepository _imageRepository;

    private static ArticleImageRepository imageRepository;

    /**
     * Negative results are re-scanned once after this. A positive result is never
     * re-scanned while its row lives - but the row is not kept forever, since
     * CleanupService purges unpinned rows every 72 hours.
     */
    private static final long NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000;

    /** Column width of article_image.image_url. */
    private static final int MAX_IMAGE_URL_LENGTH = 2048;

    @PostConstruct
    private void initStaticDao() {
        imageRepository = this._imageRepository;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * @return pageUrl -> reachable image URL, ONLY for pages that have one.
     *         Never null, never throws.
     */
    public static Map<String, String> resolveAll(Collection<String> pageUrls, Myproxy proxy) {
        Map<String, String> results = new LinkedHashMap<>();
        if (pageUrls == null || pageUrls.isEmpty()) {
            return results;
        }
        try {
            resolve(results, pageUrls, proxy);
        } catch (Exception e) {
            log.debug("Preview image batch failed entirely: {}", e.getMessage());
        }
        return results;
    }

    // ------------------------------------------------------------------
    // Core -- mutates results in place, so a throw anywhere leaves the
    // entries resolved so far intact.
    // ------------------------------------------------------------------

    private static void resolve(Map<String, String> results, Collection<String> pageUrls, Myproxy proxy) {
        Set<String> urls = new LinkedHashSet<>();
        for (String url : pageUrls) {
            if (url != null && !url.trim().isEmpty()) {
                urls.add(url);
            }
        }
        if (urls.isEmpty()) {
            return;
        }

        Map<String, String> hashByUrl = new LinkedHashMap<>();
        Set<String> hashes = new LinkedHashSet<>();
        for (String url : urls) {
            String hash = sha256(url);
            hashByUrl.put(url, hash);
            hashes.add(hash);
        }

        // --- DB cache: one query for the whole batch ---
        Map<String, ArticleImage> cached = new HashMap<>();
        try {
            if (imageRepository == null) {
                log.debug("ArticleImageCache not initialised; scanning every page");
            } else {
                for (ArticleImage row : imageRepository.findByUrlHashIn(hashes)) {
                    cached.put(row.getUrlHash(), row);
                }
            }
        } catch (Exception e) {
            log.debug("Image cache lookup failed, scanning every page: {}", e.getMessage());
        }

        // --- Classify: hit, stale negative, or miss ---
        long negativeCutoff = System.currentTimeMillis() - NEGATIVE_TTL_MS;
        Map<String, ArticleImage> pending = new LinkedHashMap<>(); // url -> row to fill in
        int hits = 0;
        for (String url : urls) {
            String hash = hashByUrl.get(url);
            ArticleImage hit = cached.get(hash);
            if (hit == null) {
                pending.put(url, new ArticleImage(hash, null));
            } else if (hit.getImageUrl() != null) {
                results.put(url, hit.getImageUrl()); // cached positive -- zero proxy calls
                hits++;
            } else if (hit.getChecked() != null && hit.getChecked().getTime() > negativeCutoff) {
                hits++; // fresh negative -- zero proxy calls
            } else {
                pending.put(url, hit); // stale negative -- re-scan UPDATES this row, no duplicate hash
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        // --- Proxy is mandatory; a page that was never scanned must not get a negative row ---
        if (proxy == null) {
            log.debug("No proxy; {} page(s) left unscanned", pending.size());
            return;
        }

        // --- Scan the rest ---
        List<ArticleImage> toPersist = new ArrayList<>();
        int found = 0;
        for (Map.Entry<String, ArticleImage> entry : pending.entrySet()) {
            String url = entry.getKey();
            ArticleImage row = entry.getValue();

            // og:image -> twitter:image -> itemprop -> image_src -> largest body image,
            // all resolved to absolute urls.
            String imageUrl = ImagePreview.find(url, proxy);
            if (imageUrl != null
                    && imageUrl.length() <= MAX_IMAGE_URL_LENGTH // would not fit the column
                    && ImagePreview.isReachable(imageUrl, proxy)) {
                results.put(url, imageUrl);
                row.setImageUrl(imageUrl);
                found++;
            } else {
                row.setImageUrl(null);
            }
            row.setChecked(new Date());
            toPersist.add(row);
        }

        persist(toPersist);
        log.debug("Preview images: {} cached, {} scanned, {} found", hits, pending.size(), found);
    }

    /**
     * The language jobs (English every 5 min, Arabic every 25, ...) can overlap if
     * the scheduler has a pool, and the same article can appear in two feeds. A
     * duplicate hash then rolls back the whole saveAll, so fall back to one-by-one
     * rather than losing the rest of the batch.
     */
    private static void persist(List<ArticleImage> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            imageRepository.saveAll(rows);
        } catch (Exception batchFailure) {
            for (ArticleImage row : rows) {
                try {
                    imageRepository.save(row);
                } catch (Exception ignored) {
                    // already inserted by a concurrent run -- harmless
                }
            }
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
