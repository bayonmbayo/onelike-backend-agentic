package com.onelike.app.controller;


import com.onelike.app.model.*;
import com.onelike.app.service.*;
import com.onelike.app.util.ArticleImageCache;
import com.onelike.app.util.Description;
import com.onelike.app.util.GoogleNewsUrlDecoder;
import com.onelike.app.util.OtherTool;
import com.onelike.app.util.ProxyAuthenticator;
import com.onelike.app.util.SocialMediaPlatforms;
import com.onelike.app.util.UrlLanguagePath;
import com.onelike.app.util.UrlWebsite;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;
import net.amygdalum.stringsearchalgorithms.search.StringFinder;
import net.amygdalum.stringsearchalgorithms.search.StringMatch;
import net.amygdalum.stringsearchalgorithms.search.chars.AhoCorasick;
import net.amygdalum.util.io.CharProvider;
import net.amygdalum.util.io.StringCharProvider;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;
import org.apache.http.client.HttpClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.*;
import java.net.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import static com.onelike.app.util.InfoMint.*;
import static java.lang.Thread.sleep;
import static net.amygdalum.stringsearchalgorithms.search.MatchOption.LONGEST_MATCH;
import static net.amygdalum.stringsearchalgorithms.search.MatchOption.NON_OVERLAP;

@Slf4j
@CrossOrigin(exposedHeaders = "last")
@RequestMapping("/service/*")
@RestController
public class SearchController {

    @Autowired
    SearchService searchService;

    @Autowired
    NewsService _newsService;

    @Autowired
    NewsBlockService _newsBlockService;

    @Autowired
    BreachService breachService;

    @Autowired
    SiteService siteService;

    @Autowired
    MyproxyService proxyService;

    @Autowired
    QuoteService quoteService;

    private static MyproxyService myproxyService;
    private static NewsService newsService;
    private static NewsBlockService newsBlockService;

    @Value("${onelike.database.searchnews}")
    private Boolean searchNews;

    /**
     * Whether the proxy pool is kept warm. Fully independent of searchnews: a
     * run can want news without the proxy churn, or the other way round. The
     * inline default keeps the behaviour unchanged where the key is absent,
     * deliberately unlike searchnews, which has none and must be set.
     */
    @Value("${onelike.database.proxies.enabled:true}")
    private Boolean proxiesEnabled;

    @Value("${onelike.hibp.api-key}")
    private String hibpApiKey;

    @Value("${onelike.webshare.api-key}")
    private String webshareApiKeyValue;

    /**
     * Static copy of {@link #webshareApiKeyValue}, assigned in initStaticDao().
     * Spring never injects static fields: an @Value on one is ignored silently and
     * the field just stays null, so the key has to arrive on an instance field and
     * be copied across. It is needed statically because replaceInactiveProxies() is
     * static and so cannot read the instance field -- the same mechanism that method
     * already relies on for myproxyService.
     */
    private static String webshareApiKey;

    /**
     * Languages that need News rows. Must match EXACTLY what each
     * SearchControllerXX passes to saveNews() -- note "Deutsch" and "Francais".
     */
    @Value("${onelike.news.languages:"
            + "English,Deutsch,Spanish,Francais,Greek,Hindi,Indonesian,Italian,"
            + "Japanese,Dutch,Portuguese,Russian,Turkish,Arabic,Chinese}")
    private String[] newsLanguages;

    @PostConstruct
    private void initStaticDao() {
        installStaticDao(this.proxyService, this._newsService, this._newsBlockService);
        webshareApiKey = this.webshareApiKeyValue;
    }

    /**
     * The single place the three static services are assigned. Package-private
     * so a test can install its OWN context's services (or a fake proxy pool)
     * immediately before a case: statics are per class loader, not per Spring
     * context, so whichever test context was created last would otherwise own
     * them. The webshare key is deliberately not part of this hook.
     */
    static void installStaticDao(MyproxyService proxies, NewsService news, NewsBlockService newsBlocks) {
        myproxyService = proxies;
        newsService = news;
        newsBlockService = newsBlocks;
    }

    @GetMapping(value = "/version")
    public String apiVersion() throws IOException {
        return "API created on June 30th 2022";
    }

    @PostMapping(value = "/searchprofil", produces = "application/json")
    public String searchProfiles(@RequestBody String searchText, HttpServletRequest request) throws Exception {
        Search search = searchService.findSearchByText(Normalizer.normalize(searchText.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", ""));

        if (search != null) {
            search.setTimes(search.getTimes() + 1);
        } else {
            search = new Search();
            search.setSearchtext(Normalizer.normalize(searchText.toLowerCase(), Normalizer.Form.NFD)
                    .replaceAll("\\p{InCombiningDiacriticalMarks}+", ""));

            search.setTimes(1);

        }
        search.setSearched(new Date());
        search.setIpaddress(request.getRemoteAddr());
        search.setSuccessful(false);
        search.setLinks(null);
        searchService.save(search);


        List<String> urlSearchs = new ArrayList<>();
        String text = Normalizer.normalize(searchText.replaceAll(" ", "+"), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        urlSearchs.add("https://www.bing.com/search?q=");
        urlSearchs.add("https://www.google.com/search?q=");
        urlSearchs.add("https://www.duckduckgo.com/?q=");
        urlSearchs.add("https://search.yahoo.com/search?p=");
        urlSearchs.add("https://yandex.com/search/?text=");
        urlSearchs.add("https://ekoru.org/?target=all&q=");
        // urlSearchs.add("https://www.ecosia.org/search?q=");
        urlSearchs.add("https://www.qwant.com/?t=web&q=");
        urlSearchs.add("https://metager.de/meta/meta.ger3?eingabe=");
        urlSearchs.add("https://search.brave.com/search?q=");
        JSONObject json = new JSONObject();
        JSONArray array = new JSONArray();
        JSONArray arrayTmp = new JSONArray();
        List<String> urls = new ArrayList<>();

        for (String urlSearch : urlSearchs) {
            Document doc = getWebsite(urlSearch, text);
            array = giveMeLinks(doc, arrayTmp, urls);
        }
        search.setLinks(urls);
        search.setResults(urls.size());
        search.setSuccessful(true);
        searchService.save(search);
        json.put("results", urls.size());
        json.put("accounts", array);

        log.debug("I am searching " + searchText + " for " + request.getRemoteAddr());

        return json.toJSONString();

    }

    public Document getWebsite(String urlSearch, String searchText) {
        long index = (long) Math.floor(Math.random() * (myproxyService.getProxyNumber()) + 1);

        Myproxy myproxy = null;
        try {
            myproxy = myproxyService.findById(index);
        } catch (Exception e) {
            e.printStackTrace();
            log.debug("Can't get the proxy");
            return null;
        }
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(myproxy.getIp(), myproxy.getPort()));
        // Proxy credentials are served by the global ProxyAuthenticator.

        Document doc = null;
        try {
            doc = Jsoup
                    .connect(urlSearch + searchText.trim().toLowerCase())
                    // .proxy(proxy)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36")
                    .get();
        } catch (Exception e) {
            e.printStackTrace();
            log.debug("Can't get the website: " + urlSearch);
            return null;
        }

        return doc;
    }

    public static Document getWebsite(String url) {
        long index = (long) Math.floor(Math.random() * (myproxyService.getProxyNumber()) + 1);

        Myproxy myproxy = null;
        try {
            myproxy = myproxyService.findById(index);
        } catch (Exception e) {
            e.printStackTrace();
            log.debug("Can't get the proxy");
            return null;
        }

        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(myproxy.getIp(), myproxy.getPort()));
        // Proxy credentials are served by the global ProxyAuthenticator.

        Document doc = null;
        try {
            doc = Jsoup
                    .connect(url)
                    .proxy(proxy)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36")
                    .followRedirects(true)
                    .get();
        } catch (Exception e) {
            e.printStackTrace();
            log.debug("Can't get the website: " + url);
            return null;
        }

        return doc;
    }

    public JSONArray giveMeLinks(Document doc, JSONArray array, List<String> urls) {
        if (doc == null) {
            return new JSONArray();
        }
        Elements links = doc.select("a[href]");
        JSONObject[] arraytmp;
        JSONObject item;
        String tmpLink;
        String url = "";
        String text = "";
        boolean canAdd = false;

        for (Element link : links) {
            url = link.attr("href");
            text = link.text();
            item = new JSONObject();

            if (url.contains("twitter")) {
                if (!url.contains("status") && (url.startsWith("https://twitter.com") || url.startsWith("https://www.twitter.com"))) {
                    // System.out.println(url);
                    item.put("socialmedia", "Twitter");
                    item.put("link", url);
                    item.put("logo", "/twitter.png");
                    item.put("description", "Twitter is a microblogging and social networking service on which users post and interact with messages known as \"tweets\".");
                    arraytmp = new JSONObject[array.size()];
                    if (array.size() == 0) {
                        array.add(item);
                        urls.add(url);
                    } else {
                        for (int i = 0; i < array.size(); i++) {
                            arraytmp[i] = (JSONObject) array.get(i);
                            tmpLink = (String) arraytmp[i].get("link");
                            // System.out.println(tmpLink);
                            if (tmpLink.equalsIgnoreCase(url)) {
                                canAdd = false;
                                break;
                            } else {
                                canAdd = true;
                            }
                        }
                        if (canAdd) {
                            array.add(item);
                            urls.add(url);
                            canAdd = false;
                        }
                    }
                }
            } else if (url.contains("facebook")) {
                if ((!url.contains("public") &&
                        !url.contains("pages") &&
                        !url.contains("videos") &&
                        !url.contains("groups") &&
                        !url.contains("posts") &&
                        !url.contains("photo") &&
                        !url.contains("r.php") &&
                        !url.contains("profile.php") &&
                        !url.contains("index.php") &&
                        !url.contains("about") &&
                        !url.contains("help") &&
                        !url.contains("marketplace") &&
                        !url.contains("livemap") &&
                        !url.contains("login") &&
                        !url.contains("facebookapp") &&
                        !url.contains("media")) && (url.startsWith("https://www.facebook.com") || url.startsWith("https://facebook.com"))) {
                    // System.out.println(url);
                    item.put("socialmedia", "Facebook");
                    item.put("link", url);
                    item.put("logo", "/facebook.png");
                    item.put("description", "Facebook is an online social media and social networking service owned by American company Meta Platforms.");
                    arraytmp = new JSONObject[array.size()];
                    if (array.size() == 0) {
                        array.add(item);
                        urls.add(url);
                    } else {
                        for (int i = 0; i < array.size(); i++) {
                            arraytmp[i] = (JSONObject) array.get(i);
                            tmpLink = (String) arraytmp[i].get("link");
                            // System.out.println(tmpLink);
                            if (tmpLink.equalsIgnoreCase(url)) {
                                canAdd = false;
                                break;
                            } else {
                                canAdd = true;
                            }
                        }
                        if (canAdd) {
                            array.add(item);
                            urls.add(url);
                            canAdd = false;
                        }
                    }
                }
            } else if (url.contains("linkedin")) {
                if (url.contains("linkedin.com/in")) {
                    if (!url.contains("https://translate.google.com")) {
                        // System.out.println(url);
                        item.put("socialmedia", "Linkedin");
                        item.put("link", url);
                        item.put("logo", "/linkedin.png");
                        item.put("description", "LinkedIn is an American business and employment-oriented online service that operates via websites and mobile apps.");
                        arraytmp = new JSONObject[array.size()];
                        if (array.size() == 0) {
                            array.add(item);
                            urls.add(url);
                        } else {
                            for (int i = 0; i < array.size(); i++) {
                                arraytmp[i] = (JSONObject) array.get(i);
                                tmpLink = (String) arraytmp[i].get("link");
                                // System.out.println(tmpLink);

                                if (tmpLink.equalsIgnoreCase(url)) {
                                    canAdd = false;
                                    break;
                                } else {
                                    canAdd = true;
                                }
                            }
                            if (canAdd) {
                                array.add(item);
                                urls.add(url);
                                canAdd = false;
                            }
                        }
                    }
                }
            } else if (url.contains("instagram")) {
                if (!url.contains("locations") && !url.contains("/p/") && !url.contains("/tv/") && !url.contains("/explore/") && url.startsWith("https://www.instagram.com")) {
                    // System.out.println(url);
                    item.put("socialmedia", "Instagram");
                    item.put("link", url);
                    item.put("logo", "/instagram.png");
                    item.put("description", "Instagram is a photo and video sharing social networking service founded in 2010 by Kevin Systrom and Mike Krieger, and later acquired by Meta Platforms.");
                    arraytmp = new JSONObject[array.size()];
                    if (array.size() == 0) {
                        array.add(item);
                        urls.add(url);
                    } else {
                        for (int i = 0; i < array.size(); i++) {
                            arraytmp[i] = (JSONObject) array.get(i);
                            tmpLink = (String) arraytmp[i].get("link");
                            // System.out.println(tmpLink);
                            if (tmpLink.equalsIgnoreCase(url)) {
                                canAdd = false;
                                break;
                            } else {
                                canAdd = true;
                            }
                        }
                        if (canAdd) {
                            array.add(item);
                            urls.add(url);
                            canAdd = false;
                        }
                    }
                }
            } else if (url.contains("xing")) {
                if (url.startsWith("https://www.xing.com/profile")) {
                    // System.out.println(url);
                    item.put("socialmedia", "Xing");
                    item.put("link", url);
                    item.put("logo", "/xing.png");
                    item.put("description", "XING is a Hamburg-based career-oriented social networking site, operated by New Work SE (until mid-2019 XING SE).");
                    arraytmp = new JSONObject[array.size()];
                    if (array.size() == 0) {
                        array.add(item);
                        urls.add(url);
                    } else {
                        for (int i = 0; i < array.size(); i++) {
                            arraytmp[i] = (JSONObject) array.get(i);
                            tmpLink = (String) arraytmp[i].get("link");
                            // System.out.println(tmpLink);
                            if (tmpLink.equalsIgnoreCase(url)) {
                                canAdd = false;
                                break;
                            } else {
                                canAdd = true;
                            }
                        }
                        if (canAdd) {
                            array.add(item);
                            urls.add(url);
                            canAdd = false;
                        }
                    }
                }
            } else if (url.contains("youtube")) {
                if (!url.contains("feature") && (url.startsWith("https://www.youtube.com/channel") || url.startsWith("https://www.youtube.com/user"))) {
                    // System.out.println(url);
                    item.put("socialmedia", "YouTube");
                    item.put("link", url);
                    item.put("logo", "/youtube.png");
                    item.put("description", "YouTube is an American online video sharing and social media platform headquartered in San Bruno, California.");
                    arraytmp = new JSONObject[array.size()];
                    if (array.size() == 0) {
                        array.add(item);
                        urls.add(url);
                    } else {
                        for (int i = 0; i < array.size(); i++) {
                            arraytmp[i] = (JSONObject) array.get(i);
                            tmpLink = (String) arraytmp[i].get("link");
                            // System.out.println(tmpLink);
                            if (tmpLink.equalsIgnoreCase(url)) {
                                canAdd = false;
                                break;
                            } else {
                                canAdd = true;
                            }
                        }
                        if (canAdd) {
                            array.add(item);
                            urls.add(url);
                            canAdd = false;
                        }
                    }
                }
                ;
            } else if (url.contains("github")) {
                if (url.startsWith("https://github.com") || url.startsWith("https://www.github.com")) {
                    // System.out.println(url);
                    item.put("socialmedia", "GitHub");
                    item.put("link", url);
                    item.put("logo", "/github.png");
                    item.put("description", "GitHub, Inc. is a provider of Internet hosting for software development and version control using Git.");
                    arraytmp = new JSONObject[array.size()];
                    if (array.size() == 0) {
                        array.add(item);
                        urls.add(url);
                    } else {
                        for (int i = 0; i < array.size(); i++) {
                            arraytmp[i] = (JSONObject) array.get(i);
                            tmpLink = (String) arraytmp[i].get("link");
                            // System.out.println(tmpLink);
                            if (tmpLink.equalsIgnoreCase(url)) {
                                canAdd = false;
                                break;
                            } else {
                                canAdd = true;
                            }
                        }
                        if (canAdd) {
                            array.add(item);
                            urls.add(url);
                            canAdd = false;
                        }
                    }
                }
            }
        }

        return array;

    }

    @GetMapping(value = "/news", produces = "application/json")
    public ResponseEntity<String> getNews(HttpServletRequest request) throws Exception {
        log.debug("I am giving news to " + request.getRemoteAddr());

        News news = newsService.retrieveEnglishNews();

        return ResponseEntity.ok(news == null ? "{\"news\" : []}" : news.getFeed());
    }

    public static JSONObject[] articlesOfAllSocialMedias(String language) throws Exception {
        // replaceInactiveProxies();

        log.debug("--------------- " + language + " News --------------");
        JSONObject[] data = new JSONObject[2];
        JSONObject socialmediaObject = new JSONObject();
        JSONObject socialmediaObjectWithoutCategory = new JSONObject();
        JSONArray socialmediaList = new JSONArray();
        JSONArray socialmediaListWithoutCategory = new JSONArray();

        // One platform's failure must not cost the other nineteen their refresh.
        // A platform that throws is NEVER skipped - 19 cards would be a shape
        // change - it gets the same 7-key card built from its saved news_block
        // row. fallbackCard guards its own lookup, so a row that cannot be read
        // (e.g. two rows for one platform and language) costs that platform an
        // empty card, not the run.
        for (String sm : SocialMediaPlatforms.ALL) {
            JSONObject card;
            try {
                card = articlesOf(language, sm, socialmediaListWithoutCategory);
            } catch (Exception e) {
                log.warn(sm + " " + language + ": the refresh threw (" + e + "), I am keeping what was previously fetched");
                card = fallbackCard(language, sm, "the refresh threw");
            }
            socialmediaList.add(card);
        }
        socialmediaObject.put("socialmedias", socialmediaList);
        socialmediaObjectWithoutCategory.put("news", sortJsonArray(socialmediaListWithoutCategory, language, "AllSocials"));

        data[0] = socialmediaObject;
        data[1] = socialmediaObjectWithoutCategory;

        return data;
    }

    /**
     * Percent-encodes the Google News query. The 20 built-in platform names are
     * single alphanumeric words, so this is a no-op for the 5-minute feed job of
     * all 15 languages; it exists for user themes, whose titles are free text
     * and may contain spaces, "&" or non-ASCII characters that would otherwise
     * be concatenated raw into the URL and either break the query string or
     * throw a MalformedURLException.
     *
     * Spaces become %20 rather than "+" to stay consistent with the pre-encoded
     * betterSearchText special cases just below.
     */
    private static String encodeSearchText(String searchText) {
        return URLEncoder.encode(searchText, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * How articlesOf turns its (unconnected) Google News URLConnection into an
     * XmlReader. Production always passes XmlReader::new, which is what opens
     * the connection; the seam exists so a test can hand back a reader over
     * fixed bytes - and override its close() - without any socket.
     */
    @FunctionalInterface
    interface XmlReaderOpener {
        XmlReader open(URLConnection connection) throws IOException;
    }

    /**
     * A platform's last saved feed, as the array the card's "news" key holds.
     *
     * Every way a stored feed can be unusable answers an EMPTY array instead of
     * throwing or leaking a non-array into the card: no block, a null feed, a
     * feed that does not parse, and a feed that parses to something other than
     * an array (MODE_PERMISSIVE turns bare text into a String and "{}" into an
     * object, neither of which a client can iterate). Before this, one corrupt
     * news_block row made articlesOf throw, and the throw took the other
     * nineteen platforms' refresh down with it.
     *
     * @param site which fallback in articlesOf is asking, for the log line only
     */
    static JSONArray savedFeedOf(NewsBlock newsBlock, String language, String searchText, String site) {
        if (newsBlock == null) {
            log.debug(searchText + " " + language + ": no saved feed yet (" + site + "), serving []");
            return new JSONArray();
        }
        String feed = newsBlock.getFeed();
        if (feed == null) {
            log.warn(searchText + " " + language + ": saved feed is null (" + site + "), serving []");
            return new JSONArray();
        }
        Object parsed;
        try {
            parsed = new JSONParser(JSONParser.MODE_PERMISSIVE).parse(feed);
        } catch (Exception e) {
            log.warn(searchText + " " + language + ": saved feed does not parse (" + site + "), serving []: " + e);
            return new JSONArray();
        }
        if (!(parsed instanceof JSONArray)) {
            log.warn(searchText + " " + language + ": saved feed is not an array (" + site + "), serving []");
            return new JSONArray();
        }
        return (JSONArray) parsed;
    }

    /**
     * The previews stored next to a platform's saved feed, as the array the
     * card's "linkspreview" key holds, keeping ONLY the entries whose url is
     * one of savedNews' urls - so a card never shows a preview of an article it
     * does not list (and an empty savedNews always means no previews).
     *
     * Like savedFeedOf, every unusable input answers an EMPTY array instead of
     * throwing: no block, a NULL column (every row stored before the column
     * existed), a value that does not parse, one that parses to a non-array,
     * and entries that are not objects.
     *
     * @param site which fallback in articlesOf is asking, for the log line only
     */
    static JSONArray savedLinksPreviewOf(NewsBlock newsBlock, JSONArray savedNews, String language,
                                         String searchText, String site) {
        JSONArray kept = new JSONArray();
        if (newsBlock == null || newsBlock.getLinkspreview() == null || savedNews.isEmpty()) {
            return kept;
        }
        Object parsed;
        try {
            parsed = new JSONParser(JSONParser.MODE_PERMISSIVE).parse(newsBlock.getLinkspreview());
        } catch (Exception e) {
            log.warn(searchText + " " + language + ": saved previews do not parse (" + site + "), serving []: " + e);
            return kept;
        }
        if (!(parsed instanceof JSONArray)) {
            log.warn(searchText + " " + language + ": saved previews are not an array (" + site + "), serving []");
            return kept;
        }
        Set<Object> listed = new HashSet<>();
        for (Object item : savedNews) {
            if (item instanceof JSONObject && ((JSONObject) item).get("url") != null) {
                listed.add(((JSONObject) item).get("url"));
            }
        }
        for (Object preview : (JSONArray) parsed) {
            if (preview instanceof JSONObject && listed.contains(((JSONObject) preview).get("url"))) {
                kept.add(preview);
            }
        }
        return kept;
    }

    /**
     * The card every degraded branch serves: the same seven keys a fresh card
     * carries, built ONLY from the platform's saved news_block row - no DB
     * call, no socket, so it cannot throw on account of the row's content.
     *
     * - news: the saved feed, through savedFeedOf;
     * - linkspreview: the previews stored with that feed, through
     *   savedLinksPreviewOf (filtered to the urls in news);
     * - last_update: the row's stored `updated`, re-wrapped as a plain
     *   java.util.Date so it serialises exactly like a fresh card's. A
     *   Hibernate-loaded value is a java.sql.Timestamp, which json-smart does
     *   not special-case: it falls through to bean reflection and serialises
     *   as {"nanos":...} instead of the Date.toString() form (see
     *   UserNewsContainerService.wireDate). Now when there is no row or no
     *   stored `updated`.
     *
     * @param newsBlock the row as already looked up, or null for none
     * @param site      which branch is serving it, for the log lines only
     */
    static JSONObject savedCard(String language, String searchText, NewsBlock newsBlock, String site) {
        log.debug(searchText + " " + language + ": serving the saved card (" + site + ")");
        JSONObject articleObject = new JSONObject();
        articleObject.put("language", language);
        articleObject.put("name", searchText);
        articleObject.put("website", UrlWebsite.getWebsiteUrl(searchText));
        articleObject.put("description", Description.getDescription(language, searchText));
        JSONArray savedNews = savedFeedOf(newsBlock, language, searchText, site);
        articleObject.put("news", savedNews);
        articleObject.put("linkspreview", savedLinksPreviewOf(newsBlock, savedNews, language, searchText, site));
        articleObject.put("last_update", newsBlock != null && newsBlock.getUpdated() != null
                ? new Date(newsBlock.getUpdated().getTime()) : new Date());
        return articleObject;
    }

    /**
     * The card articlesOfAllSocialMedias puts in place of a platform whose
     * refresh threw: savedCard over a lookup of its news_block row.
     *
     * The lookup is guarded. If it throws - e.g. the IncorrectResultSize /
     * NonUniqueResult that two rows for one platform and language produce -
     * the platform gets savedCard over no row (empty news, empty previews,
     * last_update now), still seven keys, and the other platforms' run goes
     * on. Not covered by the guard: the four static helpers savedCard calls,
     * none of which touches the DB.
     */
    static JSONObject fallbackCard(String language, String searchText, String site) {
        NewsBlock newsBlock;
        try {
            newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
        } catch (Exception e) {
            log.warn(searchText + " " + language + ": the saved row cannot be read (" + site + "), serving an empty card: " + e);
            newsBlock = null;
        }
        return savedCard(language, searchText, newsBlock, site);
    }

    public static JSONObject articlesOf(String language, String searchText, JSONArray allSocialMedias) throws Exception {
        return articlesOf(language, searchText, allSocialMedias, XmlReader::new);
    }

    static JSONObject articlesOf(String language, String searchText, JSONArray allSocialMedias,
                                 XmlReaderOpener xmlReaderOpener) throws Exception {
        log.debug("--------------- " + searchText + " " + language + " --------------");
        long index = 0;
        boolean proxyAvailable = false;
        Myproxy myproxyTmp = null;

        List<FilterNews> filterNewsList = newsService.getAllFilterNews();

        for (int i = 0; i < myproxyService.getProxyNumber(); i++) {
            index = (long) Math.floor(Math.random() * (myproxyService.getProxyNumber()) + 1);
            myproxyTmp = myproxyService.findById(index);
            isOnline(myproxyTmp);

            if (myproxyTmp.getActive()) {
                // System.out.println("proxy available " + myproxyTmp.getIp() + ":" + myproxyTmp.getPort());
                proxyAvailable = true;
                break;
            }
        }

        if (!proxyAvailable) {
            if(myproxyTmp != null){
                log.debug(searchText + " " + language + ": All Proxies " + myproxyTmp.getIp() + ":" + myproxyTmp.getPort() + " not available");
                NewsBlock newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
                return savedCard(language, searchText, newsBlock, "all proxies inactive");
            }else{
                log.debug(searchText + " " + language + ": All Proxies not available");
                NewsBlock newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
                return savedCard(language, searchText, newsBlock, "no proxy");
            }
        }


        Myproxy myproxy = myproxyService.findById(index);
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(myproxy.getIp(), myproxy.getPort()));
        // Proxy credentials are served by the global ProxyAuthenticator.
        String betterSearchText;
        URLConnection url = null;
        try {
            url = new URL("https://news.google.com/rss/search?q=" + encodeSearchText(searchText) + UrlLanguagePath.getUrlLanguagePath(language)).openConnection(proxy);
            if (searchText.equalsIgnoreCase("Xing")) {
                betterSearchText = "Xing%20social%20media";
                url = new URL("https://news.google.com/rss/search?q=" + betterSearchText + UrlLanguagePath.getUrlLanguagePath(language)).openConnection(proxy);
            } else if (searchText.equalsIgnoreCase("Signal")) {
                betterSearchText = "signal%20messaging%20app";
                url = new URL("https://news.google.com/rss/search?q=" + betterSearchText + UrlLanguagePath.getUrlLanguagePath(language)).openConnection(proxy);
            } else if (searchText.equalsIgnoreCase("Telegram")) {
                betterSearchText = "Telegram%20app";
                url = new URL("https://news.google.com/rss/search?q=" + betterSearchText + UrlLanguagePath.getUrlLanguagePath(language)).openConnection(proxy);
            }
        } catch (Exception e) {
            if(url != null){
                e.printStackTrace();
                log.debug(searchText + " " + language + ": Can't reach the URL " + url.getURL());
            }
            NewsBlock newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
            return savedCard(language, searchText, newsBlock, "unreachable URL");
        }

        url.setConnectTimeout(10000);
        url.setReadTimeout(15000);
        url.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36");

        XmlReader xmlReader = null;
        JSONObject articleObject = new JSONObject();
        JSONArray news = new JSONArray();
        JSONArray arrayForLinksPreview = new JSONArray();

        try {
            xmlReader = xmlReaderOpener.open(url);
            SyndFeed feeder = new SyndFeedInput().build(xmlReader);

            // Collect the entries we want first, so the whole feed is decoded in
            // ONE batchexecute call instead of one call per article.
            List<SyndEntry> goodEntries = new ArrayList<>();
            List<String> googleUrls = new ArrayList<>();

            for (Iterator iterator = feeder.getEntries().iterator(); iterator.hasNext(); ) {
                SyndEntry syndEntry = (SyndEntry) iterator.next();
                if (isGoodNews(syndEntry.getTitle(), filterNewsList) && !syndEntry.getTitle().contains("discord")) {
                    goodEntries.add(syndEntry);
                    googleUrls.add(syndEntry.getLink());
                }
            }

            // Anything that cannot be decoded comes back mapped to itself, so the
            // feed keeps the Google URL rather than losing the article.
            Map<String, String> realUrls = GoogleNewsUrlDecoder.decodeAll(googleUrls, myproxy);

            for (SyndEntry syndEntry : goodEntries) {
                JSONObject item = new JSONObject();
                item.put("title", syndEntry.getTitle());
                item.put("url", realUrls.get(syndEntry.getLink()));
                item.put("publishedat", syndEntry.getPublishedDate());
                item.put("timeago", toDuration(syndEntry.getPublishedDate().toString()));
                item.put("type", searchText); // Add Type of News : Twitter, Facebook, Instagram
                news.add(item);
                allSocialMedias.add(item);
            }

            articleObject.put("language", language);
            articleObject.put("name", searchText);
            articleObject.put("website", UrlWebsite.getWebsiteUrl(searchText));
            articleObject.put("description", Description.getDescription(language, searchText));
            JSONArray newsArrayJson = sortJsonArray(news, language, searchText);

            NewsBlock newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
            if(newsArrayJson.size() == 0){
                return savedCard(language, searchText, newsBlock, "empty RSS"); // the finally still closes the reader
            }

            int numPreviewImages;
            if(language.equalsIgnoreCase("English")){
                numPreviewImages = NUMBER_OF_PREVIEW_IMAGES_FOR_ENGLISH;
            }else{
                numPreviewImages = NUMBER_OF_PREVIEW_IMAGES;
            }

            if (newsArrayJson.size() >= numPreviewImages) {
                for (int i = 0; i < numPreviewImages; i++) {
                    JSONObject object = (JSONObject) newsArrayJson.get(i);
                    arrayForLinksPreview.add(object);
                }
            } else {
                for (int i = 0; i < newsArrayJson.size(); i++) {
                    JSONObject object = (JSONObject) newsArrayJson.get(i);
                    arrayForLinksPreview.add(object);
                }
            }
            // Works again now that the feed holds real publisher URLs: no browser needed.
            //
            // The previews are computed BEFORE the save so the feed and the
            // previews this card serves go into the row in ONE write per
            // refresh, and every degraded branch can later serve both from it.
            // Edge case of that ordering: if giveMeArrayOfLinks threw, the feed
            // would not be saved either (the catch below serves the saved card
            // instead). Its only fallible step, ArticleImageCache.resolveAll,
            // catches internally and answers what it resolved so far.
            JSONArray linksPreview = giveMeArrayOfLinks(myproxy, arrayForLinksPreview, language, searchText);

            if(newsBlock == null){
                newsBlock = new NewsBlock();
                newsBlock.setUpdated(new Date());
                newsBlock.setLanguage(language);
                newsBlock.setSocialmedia(searchText);
            }else{
                newsBlock.setUpdated(new Date());
            }

            newsBlock.setFeed(newsArrayJson.toJSONString());
            newsBlock.setLinkspreview(linksPreview.toJSONString());
            newsBlockService.save(newsBlock);

            articleObject.put("news", newsArrayJson);
            articleObject.put("linkspreview", linksPreview);
            articleObject.put("last_update", new Date());
        } catch (Exception e) {
            e.printStackTrace();
            log.debug(searchText + " " + language + ": Error in reading XML");
            NewsBlock newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
            articleObject = savedCard(language, searchText, newsBlock, "unreadable RSS");
        } finally {
            if (xmlReader != null) {
                try {
                    xmlReader.close();
                } catch (Exception e) {
                    // A failed close only logs. It used to overwrite the stored
                    // feed with the in-memory list - empty whenever the feed had
                    // no items or failed to parse - destroying a good saved feed
                    // and replacing the card the try/catch had already built.
                    log.warn(searchText + " " + language + ": XmlReader was not closed correctly: " + e);
                }
            }
        }



        return articleObject;
    }

    /**
     * The cache-only face of {@link #giveMeArrayOfLinks}: it answers preview
     * entries for the pages that are ALREADY in the article_image table and
     * never scans a page, never opens a connection and never spends a single
     * proxy request.
     *
     * That is not a hope about the current workload, it is a property of the
     * null proxy: {@link com.onelike.app.util.ArticleImageCache#resolveAll}
     * does its one batched findByUrlHashIn, fills the result map with the
     * positives it found, and then null-checks the proxy and returns before the
     * scan loop - so with proxy == null the unscanned pages are simply left
     * out of the answer. Nothing downstream of that check can run.
     *
     * This exists as a named method rather than as a bare giveMeArrayOfLinks(null, ...)
     * call so the intent is falsifiable: a future edit that starts
     * dereferencing proxy before the null check has to come past this javadoc,
     * and the read path that relies on "free" (UserNewsContainerService.themeJson,
     * recomputing a theme card's linkspreview on every read) is greppable.
     *
     * @param articles the preview candidates, in feed order
     * @return the entries whose page is cached with an image, in that same
     *         order; pages not in the cache are omitted, never scanned
     */
    public static JSONArray giveMeArrayOfLinksFromCache(JSONArray articles, String language, String network) {
        return giveMeArrayOfLinks(null, articles, language, network);
    }

    /**
     * @param proxy an ACTIVE proxy to scan never-before-seen pages with, or
     *              null for cache-only (see
     *              {@link #giveMeArrayOfLinksFromCache}): a null proxy cannot
     *              scan and cannot spend budget, because
     *              ArticleImageCache.resolveAll null-checks it and returns
     *              right after the batch cache query.
     */
    public static JSONArray giveMeArrayOfLinks(Myproxy proxy, JSONArray arrayForLinksPreview, String language, String network) {
        JSONArray arrayLinks = new JSONArray();

        List<String> pageUrls = new ArrayList<>();
        for (int i = 0; i < arrayForLinksPreview.size(); i++) {
            JSONObject object = (JSONObject) arrayForLinksPreview.get(i);
            // The feed already carries the publisher URL -- decoding happened at ingest.
            String realUrl = object.getAsString("url");

            if (realUrl == null || realUrl.trim().isEmpty() || GoogleNewsUrlDecoder.isGoogleNewsUrl(realUrl)) {
                continue; // never decoded; the redirect page has no og:image worth reading
            }
            pageUrls.add(realUrl);
        }

        // One call for the whole feed. Pages already in the article_image table cost
        // zero proxy requests; only never-before-seen pages go through ImagePreview
        // (page fetch + image HEAD) using the proxy that was just verified active.
        Map<String, String> images = ArticleImageCache.resolveAll(pageUrls, proxy);

        for (int i = 0; i < arrayForLinksPreview.size(); i++) {
            JSONObject object = (JSONObject) arrayForLinksPreview.get(i);
            String imageUrl = images.get(object.getAsString("url"));
            if (imageUrl == null) {
                continue;
            }

            JSONObject linkPreview = new JSONObject();
            linkPreview.put("title", object.getAsString("title"));
            linkPreview.put("url", object.getAsString("url"));
            linkPreview.put("image", imageUrl);
            linkPreview.put("timeago", object.getAsString("timeago"));
            arrayLinks.add(linkPreview);
        }
        if(arrayLinks.size() != 0){
            log.debug(network + ": I have retrieved preview images for " + language);
        }else{
            log.debug(network + ": No preview images for " + language);
        }

        return arrayLinks;
    }

    public static Boolean testImage(Myproxy myproxy, String urlParam) {
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(myproxy.getIp(), myproxy.getPort()));
        // Proxy credentials are served by the global ProxyAuthenticator.
        URL url = null;
        try {
            url = new URL(urlParam);
            HttpURLConnection huc = (HttpURLConnection) url.openConnection(proxy);
            huc.setConnectTimeout(5000);
            huc.setReadTimeout(5000);
            int responseCode = huc.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.debug("Can't get this image from the url: " + urlParam);
            return false;
        }
    }

    public static boolean isOnline(Myproxy myproxy) {
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(myproxy.getIp(), myproxy.getPort()));
        // Proxy credentials are served by the global ProxyAuthenticator.
        try {
            URLConnection url = new URL("https://www.google.com").openConnection(proxy);
            url.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36");
            url.setConnectTimeout(5000);
            url.setReadTimeout(10000);
            url.connect();
            myproxy.setActive(true);
            myproxyService.save(myproxy);
            return true;
        } catch (SocketTimeoutException e) {
            // Handle the connection timeout
            e.printStackTrace();
            log.debug("Connection timed out for proxy: " + myproxy.getIp() + ":" + myproxy.getPort());
            myproxy.setActive(false);
        } catch (IOException e) {
            // Handle other IOExceptions
            e.printStackTrace();
            log.debug("An error occurred for proxy: " + myproxy.getIp() + ":" + myproxy.getPort());
            myproxy.setActive(false);
        } catch (Exception e){
            e.printStackTrace();
            log.debug("Any error occurred for proxy: " + myproxy.getIp() + ":" + myproxy.getPort());
            myproxy.setActive(false);
        }

        try {
            myproxyService.save(myproxy);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return false;
    }

    @Scheduled(fixedRate = 900000, initialDelay = 10000)
    public void refreshProxies() {
        if(proxiesEnabled) {
            replaceInactiveProxies();
        }
    }

    @Scheduled(fixedRate = 300000)
    public void performJob() {
        if(searchNews) {
            saveNews("English");
        }
    }

    /**
     * Whether at least one card of the property-1 value
     * ({"socialmedias":[card, ...]}) carries a non-empty "news" array. A
     * refresh where none does is never stored: see saveNews.
     */
    static boolean anyCardHasNews(JSONObject allSocialNews) {
        Object cards = allSocialNews.get("socialmedias");
        if (!(cards instanceof List)) {
            return false;
        }
        for (Object card : (List<?>) cards) {
            if (card instanceof JSONObject && hasNews((JSONObject) card)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a JSONObject's "news" key holds a non-empty array: a card, or
     * the property-2 value {"news":[...]}, which performSearchNews reads.
     */
    static boolean hasNews(JSONObject holder) {
        Object news = holder.get("news");
        return news instanceof List && !((List<?>) news).isEmpty();
    }

    /**
     * Stores one refresh of a language, keeping the last good one:
     *
     * - property 1 (the cards GET /service/news serves) only if at least one
     *   card has news. When every card is a fallback with nothing in it, the
     *   previous row stays and a WARN names the language.
     * - property 2 (the flat all-socials list) only if that list is not empty.
     *
     * @return true when at least one row was stored, false when none was
     */
    public static boolean saveNews(String language)  {
        log.debug(language + ": In Save Function");
        News firstNews = null;
        News secondNews = null;
        try {
            firstNews = newsService.findByPropertyAndLanguage(1, language);
            secondNews = newsService.findByPropertyAndLanguage(2, language);
        } catch (Exception e) {
            e.printStackTrace();
            log.debug(language + " News can't get from DB");
        }

        String allSocialNews = null;
        String allSocialNewsWithoutCategory = null;
        boolean storeCards = false;
        boolean storeAllSocials = false;

        try {
            JSONObject[] TheData = articlesOfAllSocialMedias(language);
            allSocialNews = TheData[0].toJSONString();
            allSocialNewsWithoutCategory = TheData[1].toJSONString();
            // Keep the last good refresh. Each row is judged on the structure,
            // never on its serialised text: json-smart writes {"news":[]}, which
            // the old string guard ("{\"news\" : []}", with spaces) never matched.
            storeCards = anyCardHasNews(TheData[0]);
            storeAllSocials = hasNews(TheData[1]);
            log.debug(language + " News was retrieved");
        } catch (Exception e) {
            e.printStackTrace();
            log.debug(language + " News can't be retrieved");
        }

        if(allSocialNewsWithoutCategory != null && allSocialNewsWithoutCategory.length() > 20){
            log.debug(language + " News are available");
        }else{
            log.debug(language + " News are not available");
        }

        if (allSocialNews != null && !storeCards) {
            // e.g. every news_block lookup threw (two rows per platform, or a
            // column missing after a failed ALTER): 20 cards with no news would
            // overwrite the cards users were last served.
            log.warn(language + ": every card in this refresh has no news, keeping the previous cards");
        }
        if (allSocialNewsWithoutCategory != null && !storeAllSocials) {
            log.warn(language + ": the all-socials list of this refresh is empty, keeping the previous all-socials list");
        }

        if (storeCards || storeAllSocials) {
            // First run for a language has no rows yet -- create them instead of
            // skipping, which used to leave firstNews/secondNews null and then
            // hand null to the repository ("Entity must not be null").
            if (storeCards) {
                if (firstNews == null) {
                    firstNews = new News();
                    firstNews.setLanguage(language);
                    firstNews.setProperty(1);
                }
                firstNews.setUpdated(new Date());
                firstNews.setFeed(allSocialNews);
                firstNews.setLanguage(language);
                firstNews.setProperty(1);
            }
            if (storeAllSocials) {
                if (secondNews == null) {
                    secondNews = new News();
                    secondNews.setLanguage(language);
                    secondNews.setProperty(2);
                }
                secondNews.setUpdated(new Date());
                secondNews.setAllFeedWithoutCategory(allSocialNewsWithoutCategory);
                secondNews.setLanguage(language);
                secondNews.setProperty(2);
            }

            try {
                if (storeCards) {
                    newsService.save(firstNews, language);
                }
                if (storeAllSocials) {
                    newsService.saveEntireFeed(secondNews);
                }
                log.debug(language + " News was successfully stored in DB");
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                log.debug(language + " News was not successfully stored in DB");
                return false;
            }
        }

        return false;
    }

    @PostMapping(value = "/breach", produces = "application/json")
    public String searchBreaches(@RequestBody String account, HttpServletRequest request) throws Exception {

        return getBreach(request, account).toJSONString();
    }

    public JSONObject getBreach(HttpServletRequest request, String account) throws Exception {
        Breach breachDB = breachService.findByAccount(account);

        if (breachDB != null) {
            breachDB.setTimes(breachDB.getTimes() + 1);
            siteService.deleteSiteList(breachDB.getSites());
        } else {
            breachDB = new Breach();
            breachDB.setAccount(account);
            breachDB.setTimes(1);
        }
        breachDB.setSearched(new Date());
        breachDB.setIpaddress(request.getRemoteAddr());
        breachDB.setSuccessful(false);
        breachDB.setSites(new ArrayList<>());
        breachService.save(breachDB);

        JSONArray breaches = null;
        try {
            String accountUrl = account.trim().replace("@", "%40");
            URLConnection connection = new URL("https://haveibeenpwned.com/api/v3/breachedaccount/" + accountUrl + "?truncateResponse=false").openConnection();
            connection.setRequestProperty("hibp-api-key", hibpApiKey);
            connection.connect();

            breaches = (JSONArray) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException | ParseException e) {
            e.printStackTrace();
            breaches = new JSONArray();
        }

        JSONObject data = new JSONObject();
        JSONArray dataBreaches = new JSONArray();

        for (int i = 0; i < breaches.size(); i++) {
            JSONObject breach = (JSONObject) breaches.get(i);
            JSONObject dataBreach = new JSONObject();
            Site site = new Site();
            dataBreach.put("name", breach.get("Name"));
            site.setName((String) breach.get("Name"));
            dataBreach.put("domain", "https://" + breach.get("Domain"));
            site.setDomain("https://" + breach.get("Domain"));
            dataBreach.put("description", breach.get("Description"));
            site.setDescription((String) breach.get("Description"));
            dataBreach.put("logo", breach.get("LogoPath"));
            site.setLogo((String) breach.get("LogoPath"));
            dataBreaches.add(dataBreach);
            site.setBreach(breachDB);
            breachDB.addSite(site);
        }

        data.put("breaches", dataBreaches);
        breachDB.setBreaches(breachDB.getSites().size());
        breachDB.setSuccessful(true);
        breachService.save(breachDB);

        log.debug("I am giving info for " + account + " about breaches to " + request.getRemoteAddr());

        return data;
    }

    @PostMapping(value = "/searchnews", produces = "application/json")
    public String searchNews(@RequestBody String searchText, HttpServletRequest request) throws Exception {
        Boolean showAll = false;

        if(searchText.trim().equalsIgnoreCase("show__all")){
            showAll = true;
        }

        String data = performSearchNews("English", searchText, showAll);

        log.debug("I am searching info about " + searchText + " for " + request.getRemoteAddr());

        return data;
    }

    public static String performSearchNews(String language, String searchText, Boolean showAll) throws Exception {
        List<String> words = List.of(searchText.toLowerCase().split(" "));
        JSONArray foundNews = new JSONArray();
        JSONObject data = new JSONObject();


        News news = newsService.findByPropertyAndLanguage(2, language);
        JSONObject json = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(news.getAllFeedWithoutCategory());
        JSONArray allNews = (JSONArray) json.get("news");

        List<FilterNews> filterNewsList = newsService.getAllFilterNews();

        if(showAll){
            for (int i = 0; i < allNews.size(); i++) {
                JSONObject article = (JSONObject) allNews.get(i);
                String title = cleanTitle((String) article.get("title"));

                if (isGoodNews(title, filterNewsList)){
                    JSONObject item = new JSONObject();
                    item.put("title", article.get("title"));
                    item.put("url", article.get("url"));
                    item.put("publishedat", article.get("publishedat"));
                    item.put("timeago", article.get("timeago"));
                    item.put("type", article.get("type"));
                    foundNews.add(item);
                }
            }
        }else{
            for (int i = 0; i < allNews.size(); i++) {
                JSONObject article = (JSONObject) allNews.get(i);
                String title = cleanTitle((String) article.get("title"));
                String url = cleanUrl((String) article.get("url"));

                if (isGoodNews(title, filterNewsList) && (isMatching(words, title) || isMatching(words, url))) {
                    JSONObject item = new JSONObject();
                    item.put("title", article.get("title"));
                    item.put("url", article.get("url"));
                    item.put("publishedat", article.get("publishedat"));
                    item.put("timeago", article.get("timeago"));
                    item.put("type", article.get("type"));
                    foundNews.add(item);
                }
            }
        }

        data.put("news", foundNews);

        return foundNews.size() == 0 ? "{\"news\" : []}" : data.toJSONString();
    }

    public static String cleanTitle(String title) {
        String titleWithoutForwardslash = title.replaceAll("/", " ");
        String titleWithoutDash = titleWithoutForwardslash.replaceAll("-", " ");
        String titleWithoutPoint = titleWithoutDash.replaceAll("\\.", " ");
        String titleWithoutQMark = titleWithoutPoint.replaceAll("\\?", " ");
        String titleWithoutEqual = titleWithoutQMark.replaceAll("=", " ");
        String titleWithoutPercent = titleWithoutEqual.replaceAll("%", " ");
        String titleWithoutComma = titleWithoutPercent.replaceAll(",", " ");
        String titleWithoutDblPoint = titleWithoutComma.replaceAll(":", " ");
        String titleWithoutApostroph = titleWithoutDblPoint.replaceAll("'", " ");
        String titleWithoutLeftParenthese = titleWithoutApostroph.replaceAll("\\(", " ");
        String titleWithoutRightParenthese = titleWithoutLeftParenthese.replaceAll("\\)", " ");
        String cleanTitle = titleWithoutRightParenthese.replaceAll("!", " ");

        return cleanTitle;
    }

    public static String cleanUrl(String url) {
        // Guard: publisher URLs are not all https:// -- substring(8) on a shorter
        // or http:// URL would throw or mangle the host.
        String urlWithoutHttps = url.replaceFirst("^https?://", "");
        String urlWithoutForwardslash = urlWithoutHttps.replaceAll("/", " ");
        String urlWithoutDash = urlWithoutForwardslash.replaceAll("-", " ");
        String urlWithoutPoint = urlWithoutDash.replaceAll("\\.", " ");
        String urlWithoutQMark = urlWithoutPoint.replaceAll("\\?", " ");
        String urlWithoutEqual = urlWithoutQMark.replaceAll("=", " ");
        String urlWithoutPercent = urlWithoutEqual.replaceAll("%", " ");
        String urlWithoutComma = urlWithoutPercent.replaceAll(",", " ");
        String urlWithoutDblPoint = urlWithoutComma.replaceAll(":", " ");
        String urlWithoutApostroph = urlWithoutDblPoint.replaceAll("'", " ");
        String urlWithoutLeftParenthese = urlWithoutApostroph.replaceAll("\\(", " ");
        String urlWithoutRightParenthese = urlWithoutLeftParenthese.replaceAll("\\)", " ");
        String cleanUrl = urlWithoutRightParenthese.replaceAll("!", " ");

        return cleanUrl;
    }

    public static boolean isMatching(List<String> words, String sentence) {
        AhoCorasick stringSearch = new AhoCorasick(words);
        CharProvider text = new StringCharProvider(sentence.toLowerCase(), 0);
        StringFinder finder = stringSearch.createFinder(text, LONGEST_MATCH, NON_OVERLAP);
        List<StringMatch> all = finder.findAll();

        if (all.size() == 0) {
            return false;
        }

        return true;
    }

    public static JSONArray sortJsonArray(JSONArray array, String language, String network) {
        JSONArray sortedNews = new JSONArray();
        List<JSONObject> jsons = new ArrayList<JSONObject>();
        for (int i = 0; i < array.size(); i++) {
            jsons.add((JSONObject) array.get(i));
        }
        Collections.sort(jsons, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject lhs, JSONObject rhs) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("E MMM d H:m:s z u", Locale.ENGLISH);
                LocalDateTime d1 = LocalDateTime.parse(lhs.get("publishedat").toString(), formatter);
                LocalDateTime d2 = LocalDateTime.parse(rhs.get("publishedat").toString(), formatter);
                int result = d1.compareTo(d2);
                result = ((-1) * result);
                if (0 == result) {
                    result = d1.toLocalTime().compareTo(d2.toLocalTime());
                }
                return result;
            }
        });

        try {
            sortedNews = (JSONArray) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(jsons.toString());
        } catch (Exception e) {
            e.printStackTrace();
            log.debug(network + ": Can't be parsed");
            return sortedNews;
        }

        if(sortedNews.size() != 0){
            log.debug(network + ": I sorted " + language + " News");
        }else{
            log.debug(network + ": No sorted " + language + " News");
        }

        return sortedNews;
    }

    public static final List<Long> times = Arrays.asList(
            TimeUnit.DAYS.toMillis(365),
            TimeUnit.DAYS.toMillis(30),
            TimeUnit.DAYS.toMillis(1),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.MINUTES.toMillis(1),
            TimeUnit.SECONDS.toMillis(1));
    public static final List<String> timesString = Arrays.asList("y", "m", "d", "h", "min", "s");

    public static String toDuration(String date) {

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("E MMM d H:m:s z u", Locale.ENGLISH);
        LocalDateTime localDate = LocalDateTime.parse(date, formatter);
        long timeInMilliseconds = LocalDateTime.now().atZone(ZoneOffset.UTC).toInstant().toEpochMilli() - localDate.atOffset(ZoneOffset.UTC).toInstant().toEpochMilli();

        StringBuffer res = new StringBuffer();
        for (int i = 0; i < times.size(); i++) {
            Long current = times.get(i);
            long temp = timeInMilliseconds / current;
            if (temp > 0) {
                res.append(temp).append(" ").append(timesString.get(i));
                break;
            }
        }
        if ("".equals(res.toString()))
            return "0 s";
        else
            return res.toString();
    }

    @GetMapping(value = "/quotes", produces = "application/json")
    public List<Quote> getQuotes(HttpServletRequest request) throws Exception {
        return quoteService.getAllQuotes();
    }

    /**
     * Was a headless-Firefox RemoteWebDriver that loaded the Google News page and
     * clicked the consent button by CSS class. Replaced by the batchexecute RPC:
     * no browser, no sleep, and it returns the original URL when it cannot decode.
     */
    public static String getRealUrl(String url, Myproxy proxy) {
        return GoogleNewsUrlDecoder.decode(url, proxy);
    }

    public static void replaceInactiveProxies(){
        log.debug("--------------- Replacing Proxies --------------");

        JSONObject proxy_raw = null;
        try {
            URLConnection connection = new URL("https://proxy.webshare.io/api/v2/proxy/list/?mode=direct&page=1&page_size=80").openConnection();
            connection.setRequestProperty("Authorization", webshareApiKey);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36");
            connection.connect();

            proxy_raw = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            JSONArray proxies = (JSONArray) proxy_raw.get("results");
            // System.out.println(proxies.toJSONString());
            if (myproxyService.getProxyNumber() == 0) {
                for (int i = 0; i < proxies.size(); i++) {
                    JSONObject proxy = (JSONObject) proxies.get(i);
                    Myproxy myproxy = new Myproxy();
                    String ip = (String) proxy.get("proxy_address");
                    int port = (int) proxy.get("port");
                    myproxy.setIp(ip);
                    myproxy.setPort(port);
                    myproxy.setProxyuser((String) proxy.get("username"));
                    myproxy.setProxypwd((String) proxy.get("password"));
                    myproxy.setActive(true);
                    myproxyService.save(myproxy);
                }

            } else {
                for (int i = 0; i < proxies.size(); i++) {
                    // System.out.println("1. Replacing proxy");
                    JSONObject proxy = (JSONObject) proxies.get(i);
                    String ip = (String) proxy.get("proxy_address");
                    int port = (int) proxy.get("port");
                    String username = (String) proxy.get("username");
                    String password = (String) proxy.get("password");
                    // System.out.println("Exist: " + myproxyService.findByIpAndPort(ip, port));
                    if(myproxyService.findByIpAndPort(ip, port) == null){
                        ArrayList<Myproxy> inactiveProxies = (ArrayList<Myproxy>) myproxyService.findByActive(false);
                        if(inactiveProxies != null && inactiveProxies.size() > 0) {
                            // System.out.println("2. Replacing proxy " + ip + ":" + port);
                            Myproxy replacing_proxy = inactiveProxies.get(0);
                            replacing_proxy.setIp(ip);
                            replacing_proxy.setPort(port);
                            replacing_proxy.setProxyuser(username);
                            replacing_proxy.setProxypwd(password);
                            replacing_proxy.setActive(true);
                            myproxyService.save(replacing_proxy);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Credentials may have been rotated above -- drop the cached ones.
            ProxyAuthenticator.evictCache();
        }
    }

    public static Boolean isGoodNews(String news, List<FilterNews> filterNewsList){
        for(FilterNews filterNews : filterNewsList){
            if(OtherTool.endsWith(news, "- " + filterNews.getName())){
                return false;
            }
        }

        return true;
    }
}