package com.onelike.app.util;

import com.onelike.app.model.GoogleNewsLink;
import com.onelike.app.model.Myproxy;
import com.onelike.app.repository.GoogleNewsLinkRepository;
import com.onelike.app.service.MyproxyService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import net.minidev.json.parser.JSONParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Google News RSS links (news.google.com/rss/articles/CBMi...) to the
 * publisher URL behind them. Replaces the Selenium-based getRealUrl().
 *
 *   1. GET the article page through a proxy -> scrape data-n-a-sg + data-n-a-ts
 *   2. POST all of them in ONE batchexecute call -> publisher URLs
 *
 * Everything is static and reached through the same initStaticDao() pattern
 * SearchController uses, because the callers (articlesOf, giveMeArrayOfLinks)
 * are static and shared by every language controller -- SearchControllerAR and
 * friends get this for free without any change of their own.
 *
 * Two guarantees the callers rely on:
 *   - Every request goes through a proxy. If no active proxy is available,
 *     nothing is decoded; direct connections are never attempted.
 *   - This never throws and never returns null. On any failure -- proxy down,
 *     consent wall, expired article, 429, markup change -- the ORIGINAL Google
 *     URL is returned unchanged, so the feed degrades instead of losing items.
 */
@Slf4j
@Component
public class GoogleNewsUrlDecoder {

    @Autowired
    GoogleNewsLinkRepository _linkRepository;

    @Autowired
    MyproxyService _myproxyService;

    /**
     * Consent cookie encoding an already-made choice. Without it, an EU proxy exit
     * gets 302'd to consent.google.com and the page carries no signature. Harmless
     * on US exits. These values rotate -- refresh from a browser
     * (news.google.com -> answer banner -> DevTools -> Cookies -> .google.com -> SOCS)
     * and change the property; no redeploy needed.
     */
    @Value("${onelike.googlenews.socs:CAISNQgQEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjQwNjE2LjAyX3AwGgJlbiADGgYIgPTKswY}")
    private String _consentCookie;

    private static GoogleNewsLinkRepository linkRepository;
    private static MyproxyService myproxyService;
    private static String consentCookie;

    @PostConstruct
    private void initStaticDao() {
        linkRepository = this._linkRepository;
        myproxyService = this._myproxyService;
        consentCookie = this._consentCookie;
    }

    private static final String BATCH_URL =
            "https://news.google.com/_/DotsSplashUi/data/batchexecute";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private static final Pattern ARTICLE_ID = Pattern.compile("/(?:rss/)?articles/([^/?#]+)");
    private static final Pattern SIGNATURE = Pattern.compile("data-n-a-sg=\"([^\"]+)\"");
    private static final Pattern TIMESTAMP = Pattern.compile("data-n-a-ts=\"([^\"]+)\"");

    private static final String INNER_SKELETON =
            "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\","
                    + "null,1,null,null,null,null,null,0,1],\"X\",\"X\",1,[1,1,1],1,1,"
                    + "null,0,0,null,0],\"%s\",%s,\"%s\"]";

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Resolve one URL. Prefer decodeAll when you have several -- this issues its
     * own RPC call, and one call per article is what earns 429s.
     */
    public static String decode(String googleUrl, Myproxy proxy) {
        return decodeAll(List.of(googleUrl), proxy).get(googleUrl);
    }

    /**
     * Resolve a batch. Every input URL is a key in the result -- unresolvable
     * entries map to themselves, so the caller can never misalign the output.
     */
    public static Map<String, String> decodeAll(List<String> googleUrls, Myproxy proxy) {
        Map<String, String> results = new LinkedHashMap<>();
        if (googleUrls == null || googleUrls.isEmpty()) {
            return results;
        }
        for (String url : googleUrls) {
            results.put(url, url); // the fallback is in place before anything can fail
        }
        if (linkRepository == null || myproxyService == null) {
            log.warn("GoogleNewsUrlDecoder not initialised; returning undecoded URLs");
            return results;
        }
        try {
            resolveAll(results, proxy);
        } catch (Exception e) {
            log.debug("Decode batch failed entirely, returning undecoded URLs: {}", e.getMessage());
        }
        return results;
    }

    // ------------------------------------------------------------------
    // Core -- mutates results in place, so a throw anywhere leaves the
    // untouched entries holding their original Google URL.
    // ------------------------------------------------------------------

    private static void resolveAll(Map<String, String> results, Myproxy proxy) {
        Map<String, String> idByUrl = new LinkedHashMap<>();
        for (String googleUrl : results.keySet()) {
            String articleId = extractArticleId(googleUrl);
            if (articleId != null) {
                idByUrl.put(googleUrl, articleId);
            }
        }
        if (idByUrl.isEmpty()) {
            return;
        }

        // --- DB cache: one query for the whole batch ---
        Map<String, String> hashByUrl = new HashMap<>();
        Set<String> hashes = new HashSet<>();
        for (Map.Entry<String, String> entry : idByUrl.entrySet()) {
            String hash = sha256(entry.getValue());
            hashByUrl.put(entry.getKey(), hash);
            hashes.add(hash);
        }

        Map<String, String> cached = new HashMap<>();
        try {
            for (GoogleNewsLink link : linkRepository.findByArticleHashIn(hashes)) {
                cached.put(link.getArticleHash(), link.getPublisherUrl());
            }
        } catch (Exception e) {
            log.debug("Link cache lookup failed, decoding from scratch: {}", e.getMessage());
        }

        List<Job> pending = new ArrayList<>();
        for (Map.Entry<String, String> entry : idByUrl.entrySet()) {
            String hit = cached.get(hashByUrl.get(entry.getKey()));
            if (hit != null) {
                results.put(entry.getKey(), hit);
            } else {
                pending.add(new Job(entry.getKey(), entry.getValue(), hashByUrl.get(entry.getKey())));
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        // --- Proxy is mandatory ---
        Myproxy activeProxy = (proxy != null) ? proxy : pickActiveProxy();
        if (activeProxy == null) {
            log.debug("No active proxy available; {} URL(s) left undecoded", pending.size());
            return;
        }

        // --- Step 1: one signature request per article ---
        List<Job> ready = new ArrayList<>();
        for (Job job : pending) {
            Signature signature = fetchSignature(job.articleId, activeProxy);
            if (signature != null) {
                job.signature = signature;
                ready.add(job);
            }
        }
        if (ready.isEmpty()) {
            return;
        }

        // --- Step 2: one RPC for all of them ---
        List<GoogleNewsLink> toPersist = new ArrayList<>();
        try {
            List<String> resolved = callBatchExecute(ready, activeProxy);
            for (int i = 0; i < ready.size() && i < resolved.size(); i++) {
                String publisherUrl = resolved.get(i);
                if (publisherUrl == null
                        || !publisherUrl.startsWith("http")
                        || publisherUrl.length() > 2048) { // would not fit the column
                    continue;
                }
                Job job = ready.get(i);
                results.put(job.googleUrl, publisherUrl);
                toPersist.add(new GoogleNewsLink(job.articleHash, publisherUrl));
            }
        } catch (Exception e) {
            log.debug("batchexecute failed for {} article(s): {}", ready.size(), e.getMessage());
        }

        persist(toPersist);
        log.debug("Decoded {}/{} Google News links", toPersist.size(), pending.size());
    }

    /**
     * The language jobs (English every 5 min, Arabic every 25, ...) can overlap if
     * the scheduler has a pool, and the same article can appear in two feeds. A
     * duplicate hash then rolls back the whole saveAll, so fall back to one-by-one
     * rather than losing the rest of the batch.
     */
    private static void persist(List<GoogleNewsLink> links) {
        if (links.isEmpty()) {
            return;
        }
        try {
            linkRepository.saveAll(links);
        } catch (Exception batchFailure) {
            for (GoogleNewsLink link : links) {
                try {
                    linkRepository.save(link);
                } catch (Exception ignored) {
                    // already inserted by a concurrent run -- harmless
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Step 1 -- signature
    // ------------------------------------------------------------------

    private static Signature fetchSignature(String articleId, Myproxy proxy) {
        HttpURLConnection connection = null;
        try {
            connection = open("https://news.google.com/rss/articles/" + articleId
                    + "?hl=en-US&gl=US&ceid=US%3Aen", proxy);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            String html = readBody(connection);
            String head = html.substring(0, Math.min(4000, html.length()));
            if (connection.getURL().getHost().contains("consent.google.com")
                    || head.contains("consent.google.com")) {
                log.warn("Consent interstitial via proxy {}:{} -- refresh onelike.googlenews.socs",
                        proxy.getIp(), proxy.getPort());
                return null;
            }

            Matcher signature = SIGNATURE.matcher(html);
            Matcher timestamp = TIMESTAMP.matcher(html);
            if (!signature.find() || !timestamp.find()) {
                return null; // markup changed, or the article expired
            }
            return new Signature(signature.group(1), timestamp.group(1));

        } catch (Exception e) {
            log.debug("Signature fetch failed for {}: {}", articleId, e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------
    // Step 2 -- batched RPC
    // ------------------------------------------------------------------

    private static List<String> callBatchExecute(List<Job> jobs, Myproxy proxy) throws Exception {
        JSONArray requests = new JSONArray();
        for (Job job : jobs) {
            JSONArray request = new JSONArray();
            request.add("Fbv4je");
            request.add(String.format(INNER_SKELETON,
                    job.articleId, job.signature.timestamp(), job.signature.value()));
            requests.add(request); // json-smart escapes the nested JSON string
        }
        JSONArray envelope = new JSONArray();
        envelope.add(requests);

        byte[] body = ("f.req=" + URLEncoder.encode(envelope.toJSONString(), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = null;
        try {
            connection = open(BATCH_URL, proxy);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded;charset=UTF-8");
            connection.setFixedLengthStreamingMode(body.length);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int status = connection.getResponseCode();
            if (status == 429) {
                throw new IllegalStateException("batchexecute returned 429 -- rotate the proxy");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("batchexecute returned HTTP " + status);
            }
            return parseBatchResponse(readBody(connection));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * batchexecute replies with an anti-JSON-hijack guard, then alternating
     * byte-length lines and JSON arrays. Scan for the arrays -- do NOT split on
     * the blank line and parse what follows: the length prefix is not JSON.
     */
    private static List<String> parseBatchResponse(String responseBody) {
        List<String> urls = new ArrayList<>();
        JSONParser parser = new JSONParser(JSONParser.MODE_PERMISSIVE);

        for (String line : responseBody.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("[")) {
                continue;
            }
            Object parsed;
            try {
                parsed = parser.parse(trimmed);
            } catch (Exception ignored) {
                continue;
            }
            if (!(parsed instanceof JSONArray rows)) {
                continue;
            }
            for (Object row : rows) {
                if (!(row instanceof JSONArray cells) || cells.size() < 3
                        || !"wrb.fr".equals(cells.get(0))
                        || !(cells.get(2) instanceof String payload)) {
                    continue;
                }
                try {
                    Object inner = new JSONParser(JSONParser.MODE_PERMISSIVE).parse(payload);
                    urls.add(inner instanceof JSONArray fields && fields.size() > 1
                            ? String.valueOf(fields.get(1)) : null);
                } catch (Exception ignored) {
                    urls.add(null); // keep positional alignment with the request order
                }
            }
        }
        return urls;
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** Always proxied. Credentials come from the global ProxyAuthenticator. */
    private static HttpURLConnection open(String url, Myproxy myproxy) throws Exception {
        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(myproxy.getIp(), myproxy.getPort()));

        HttpURLConnection connection =
                (HttpURLConnection) URI.create(url).toURL().openConnection(proxy);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        connection.setRequestProperty("Cookie", "SOCS=" + consentCookie);
        return connection;
    }

    private static Myproxy pickActiveProxy() {
        try {
            List<Myproxy> active = new ArrayList<>(myproxyService.findByActive(true));
            if (active.isEmpty()) {
                return null;
            }
            return active.get((int) (Math.random() * active.size()));
        } catch (Exception e) {
            log.debug("Could not pick an active proxy: {}", e.getMessage());
            return null;
        }
    }

    private static String readBody(HttpURLConnection connection) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    public static boolean isGoogleNewsUrl(String url) {
        return extractArticleId(url) != null;
    }

    private static String extractArticleId(String url) {
        if (url == null || !url.contains("news.google.com")) {
            return null;
        }
        try {
            Matcher matcher = ARTICLE_ID.matcher(URI.create(url.trim()).getPath());
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception e) {
            return null;
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

    private record Signature(String value, String timestamp) {
    }

    private static final class Job {
        final String googleUrl;
        final String articleId;
        final String articleHash;
        Signature signature;

        Job(String googleUrl, String articleId, String articleHash) {
            this.googleUrl = googleUrl;
            this.articleId = articleId;
            this.articleHash = articleHash;
        }
    }
}