package com.onelike.app.util;

import com.onelike.app.model.Myproxy;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Finds a preview image for an article page. Extracted from
 * InfoController.getImagePreviewUrl() so SearchController can use it too.
 *
 * Order of preference: Open Graph -> Twitter card -> schema.org itemprop ->
 * link rel=image_src -> largest usable image in the body.
 *
 * Differences from the original:
 *  - Resolves RELATIVE urls. The original returned whatever was in the attribute,
 *    so "/media/x.jpg" and "//cdn.site.com/x.jpg" came back unusable. This is the
 *    single biggest cause of broken previews and it is fixed with absUrl().
 *  - A non-numeric width ("100%", "auto") no longer throws. In the original that
 *    NumberFormatException escaped the body-image lambda into the outer catch,
 *    which returned null and threw away the og:image fallbacks that had already
 *    been skipped past.
 *  - Reads srcset, so responsive images yield the largest variant instead of the
 *    smallest placeholder.
 *  - Skips tracking pixels, spacers, sprites and data: URIs in the body fallback.
 *  - Caps the download and handles non-HTML responses instead of throwing.
 *  - Sets no global Authenticator -- credentials come from ProxyAuthenticator.
 */
@Slf4j
public final class ImagePreview {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private static final int TIMEOUT_MS = 8_000;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** Below this, an image is decoration rather than a preview. */
    private static final int MIN_DIMENSION = 200;

    /** Checked in document order -- first hit wins. */
    private static final List<String> META_SELECTORS = List.of(
            "meta[property=og:image:secure_url]",
            "meta[property=og:image:url]",
            "meta[property=og:image]",
            "meta[name=og:image]",
            "meta[name=twitter:image]",
            "meta[property=twitter:image]",
            "meta[name=twitter:image:src]",
            "meta[itemprop=image]");

    /** Applied to the body fallback only -- a publisher's og:image may legitimately be a logo. */
    private static final List<String> JUNK_MARKERS = List.of(
            "spacer", "placeholder", "blank.gif", "pixel", "1x1", "transparent",
            "sprite", "avatar", "icon", "doubleclick", "/ads/", "/ad/", "track");

    private ImagePreview() {
    }

    // ------------------------------------------------------------------

    /**
     * @return an absolute image URL, or null. Never throws.
     */
    public static String find(String pageUrl, Myproxy myproxy) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return null;
        }

        Document document = fetch(pageUrl, myproxy);
        if (document == null) {
            return null;
        }

        String fromMeta = fromMetaTags(document);
        if (fromMeta != null) {
            return fromMeta;
        }

        Element linkImage = document.selectFirst("link[rel=image_src]");
        if (linkImage != null) {
            String href = linkImage.absUrl("href");
            if (isUsable(href)) {
                return href;
            }
        }

        return fromBody(document);
    }

    /**
     * HEAD the image to confirm it actually serves. Cheaper than the original
     * GET-based check and it does not pull the bytes down.
     */
    public static boolean isReachable(String imageUrl, Myproxy myproxy) {
        if (!isUsable(imageUrl)) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(myproxy.getIp(), myproxy.getPort()));
            connection = (HttpURLConnection) URI.create(imageUrl).toURL().openConnection(proxy);
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setRequestProperty("User-Agent", USER_AGENT);

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return false;
            }
            // Some CDNs answer 200 with an HTML error page.
            String contentType = connection.getContentType();
            return contentType == null || contentType.toLowerCase(Locale.ROOT).startsWith("image/");

        } catch (Exception e) {
            log.debug("Image not reachable {}: {}", imageUrl, e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------

    private static Document fetch(String pageUrl, Myproxy myproxy) {
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(myproxy.getIp(), myproxy.getPort()));

            return Jsoup.connect(pageUrl)
                    .proxy(proxy)
                    .userAgent(USER_AGENT)
                    .referrer("https://www.google.com/")
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(MAX_BODY_BYTES)
                    .followRedirects(true)
                    .ignoreHttpErrors(false)
                    .get();

        } catch (org.jsoup.UnsupportedMimeTypeException e) {
            log.debug("Not an HTML page, no preview: {}", pageUrl);
            return null;
        } catch (Exception e) {
            // Paywalls, 403s, timeouts -- a meaningful share of publishers simply
            // will not serve us. Expected, not exceptional.
            log.debug("Could not fetch {}: {}", pageUrl, e.getMessage());
            return null;
        }
    }

    private static String fromMetaTags(Document document) {
        for (String selector : META_SELECTORS) {
            for (Element meta : document.select(selector)) {
                String candidate = meta.absUrl("content");
                if (isUsable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String fromBody(Document document) {
        String best = null;
        long bestArea = -1;

        for (Element img : document.select("img")) {
            String candidate = bestFromSrcset(img);
            if (candidate == null) {
                candidate = img.absUrl("src");
            }
            if (!isUsable(candidate) || looksLikeJunk(candidate)) {
                continue;
            }

            int width = parseDimension(img.attr("width"));
            int height = parseDimension(img.attr("height"));

            // Declared and clearly too small -> decoration. Undeclared (0) is kept,
            // because plenty of article images carry no width/height at all.
            if ((width > 0 && width < MIN_DIMENSION) || (height > 0 && height < MIN_DIMENSION)) {
                continue;
            }

            long area = (long) Math.max(width, 1) * Math.max(height, 1);
            if (area > bestArea) {
                bestArea = area;
                best = candidate;
            }
        }
        return best;
    }

    /** "a.jpg 320w, b.jpg 1024w" or "a.jpg 1x, b.jpg 2x" -> the largest variant. */
    private static String bestFromSrcset(Element img) {
        String srcset = img.attr("srcset");
        if (srcset.isBlank()) {
            return null;
        }

        String best = null;
        int bestWeight = -1;

        for (String entry : srcset.split(",")) {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isBlank()) {
                continue;
            }
            int weight = 1;
            if (parts.length > 1) {
                String descriptor = parts[1].trim().toLowerCase(Locale.ROOT);
                weight = parseDimension(descriptor.replaceAll("[wx]$", ""));
            }
            if (weight > bestWeight) {
                bestWeight = weight;
                best = img.absUrl("srcset").isBlank() ? parts[0] : resolve(img, parts[0]);
            }
        }
        return best;
    }

    private static String resolve(Element context, String candidate) {
        try {
            return URI.create(context.baseUri()).resolve(candidate.trim()).toString();
        } catch (Exception e) {
            return candidate.trim();
        }
    }

    private static boolean isUsable(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false; // data: URIs, unresolved relatives, javascript:
        }
        // SVGs are usually logos, and many clients will not render them in a card.
        return !lower.contains(".svg");
    }

    private static boolean looksLikeJunk(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String marker : JUNK_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Never throws -- "100%", "auto" and "" all become 0. */
    private static int parseDimension(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}