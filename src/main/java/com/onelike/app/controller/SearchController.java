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

    public static final String HIBP_API_KEY = "a889a0d81a4a4103b0ece60b1b8353d7";
    public static final String WEBSHARE_API_KEY = "6nsvg241gh1gexuzxh3fcba3p7nggmk09d1rx6w1";

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
        myproxyService = this.proxyService;
        newsService = this._newsService;
        newsBlockService = this._newsBlockService;
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

        for (String sm : SocialMediaPlatforms.ALL) {
            socialmediaList.add(articlesOf(language, sm, socialmediaListWithoutCategory));
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

    public static JSONObject articlesOf(String language, String searchText, JSONArray allSocialMedias) throws Exception {
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
                JSONObject articleObject = new JSONObject();
                log.debug(searchText + " " + language + ": All Proxies " + myproxyTmp.getIp() + ":" + myproxyTmp.getPort() + " not available");
                articleObject.put("language", language);
                articleObject.put("name", searchText);
                articleObject.put("website", UrlWebsite.getWebsiteUrl(searchText));
                articleObject.put("description", Description.getDescription(language, searchText));

                NewsBlock newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
                if(newsBlock == null){
                    articleObject.put("news", new JSONArray());
                }else{
                    articleObject.put("news", new JSONParser(JSONParser.MODE_PERMISSIVE).parse(newsBlock.getFeed()));
                }

                articleObject.put("linkspreview", new JSONArray());
                articleObject.put("last_update", new Date());

                return articleObject;
            }else{
                JSONObject articleObject = new JSONObject();
                log.debug(searchText + " " + language + ": All Proxies not available");
                articleObject.put("language", language);
                articleObject.put("name", searchText);
                articleObject.put("website", UrlWebsite.getWebsiteUrl(searchText));
                articleObject.put("description", Description.getDescription(language, searchText));

                NewsBlock newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
                if(newsBlock == null){
                    articleObject.put("news", new JSONArray());
                }else{
                    articleObject.put("news", new JSONParser(JSONParser.MODE_PERMISSIVE).parse(newsBlock.getFeed()));
                }

                articleObject.put("linkspreview", new JSONArray());
                articleObject.put("last_update", new Date());

                return articleObject;
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
            JSONObject articleObject = new JSONObject();
            if(url != null){
                e.printStackTrace();
                log.debug(searchText + " " + language + ": Can't reach the URL " + url.getURL());
            }
            articleObject.put("language", language);
            articleObject.put("name", searchText);
            articleObject.put("website", UrlWebsite.getWebsiteUrl(searchText));
            articleObject.put("description", Description.getDescription(language, searchText));

            NewsBlock newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
            if(newsBlock == null){
                articleObject.put("news", new JSONArray());
            }else{
                articleObject.put("news", new JSONParser(JSONParser.MODE_PERMISSIVE).parse(newsBlock.getFeed()));
            }

            articleObject.put("linkspreview", new JSONArray());
            articleObject.put("last_update", new Date());

            return articleObject;
        }

        url.setConnectTimeout(10000);
        url.setReadTimeout(15000);
        url.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36");

        XmlReader xmlReader = null;
        JSONObject articleObject = new JSONObject();
        JSONArray news = new JSONArray();
        JSONArray arrayForLinksPreview = new JSONArray();

        try {
            xmlReader = new XmlReader(url);
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
                if(newsBlock == null){
                    articleObject.put("news", new JSONArray());
                }else{
                    articleObject.put("news", new JSONParser(JSONParser.MODE_PERMISSIVE).parse(newsBlock.getFeed()));
                }
            }else{
                if(newsBlock == null){
                    newsBlock = new NewsBlock();
                    newsBlock.setUpdated(new Date());
                    newsBlock.setLanguage(language);
                    newsBlock.setSocialmedia(searchText);
                }else{
                    newsBlock.setUpdated(new Date());
                }

                newsBlock.setFeed(newsArrayJson.toJSONString());
                articleObject.put("news", newsArrayJson);
                newsBlockService.save(newsBlock);
            }

            articleObject.put("last_update", new Date());

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
            articleObject.put("linkspreview", giveMeArrayOfLinks(myproxy, arrayForLinksPreview, language, searchText));
        } catch (Exception e) {
            e.printStackTrace();
            log.debug(searchText + " " + language + ": Error in reading XML");
            articleObject.put("language", language);
            articleObject.put("name", searchText);
            articleObject.put("website", UrlWebsite.getWebsiteUrl(searchText));
            articleObject.put("description", Description.getDescription(language, searchText));

            NewsBlock newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
            if(newsBlock == null){
                articleObject.put("news", new JSONArray());
            }else{
                articleObject.put("news", new JSONParser(JSONParser.MODE_PERMISSIVE).parse(newsBlock.getFeed()));
            }

            articleObject.put("linkspreview", new JSONArray());
            articleObject.put("last_update", new Date());
        } finally {
            if (xmlReader != null) {
                try {
                    xmlReader.close();
                } catch (Exception e) {
                    e.printStackTrace();
                    log.debug(searchText + " " + language + ": XmlReader was not closed correctly");
                    articleObject.put("language", language);
                    articleObject.put("name", searchText);
                    articleObject.put("website", UrlWebsite.getWebsiteUrl(searchText));
                    articleObject.put("description", Description.getDescription(language, searchText));
                    JSONArray newsArrayJson = sortJsonArray(news, language, searchText);

                    NewsBlock newsBlock = newsBlockService.findBySocialMediaAndLanguage(searchText, language);
                    if(newsBlock == null){
                        newsBlock = new NewsBlock();
                        newsBlock.setUpdated(new Date());
                        newsBlock.setLanguage(language);
                        newsBlock.setSocialmedia(searchText);
                    }else{
                        newsBlock.setUpdated(new Date());
                    }

                    newsBlock.setFeed(newsArrayJson.toJSONString());
                    articleObject.put("news", newsArrayJson);
                    newsBlockService.save(newsBlock);

                    articleObject.put("linkspreview", new JSONArray());
                    articleObject.put("last_update", new Date());
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

        try {
            JSONObject[] TheData = articlesOfAllSocialMedias(language);
            allSocialNews = TheData[0].toJSONString();
            allSocialNewsWithoutCategory = TheData[1].toJSONString();
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

        if (allSocialNewsWithoutCategory != null && !allSocialNewsWithoutCategory.trim().equalsIgnoreCase("{\"news\" : []}")) {
            // First run for a language has no rows yet -- create them instead of
            // skipping, which used to leave firstNews/secondNews null and then
            // hand null to the repository ("Entity must not be null").
            if (firstNews == null) {
                firstNews = new News();
                firstNews.setLanguage(language);
                firstNews.setProperty(1);
            }
            if (secondNews == null) {
                secondNews = new News();
                secondNews.setLanguage(language);
                secondNews.setProperty(2);
            }

            firstNews.setUpdated(new Date());
            firstNews.setFeed(allSocialNews);
            firstNews.setLanguage(language);
            firstNews.setProperty(1);

            secondNews.setUpdated(new Date());
            secondNews.setAllFeedWithoutCategory(allSocialNewsWithoutCategory);
            secondNews.setLanguage(language);
            secondNews.setProperty(2);

            try {
                newsService.save(firstNews, language);
                newsService.saveEntireFeed(secondNews);
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
            connection.setRequestProperty("hibp-api-key", HIBP_API_KEY);
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
            connection.setRequestProperty("Authorization", WEBSHARE_API_KEY);
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