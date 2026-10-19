package com.onelike.app.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.onelike.app.model.ArticleImage;
import com.onelike.app.model.Myproxy;
import com.onelike.app.model.News;
import com.onelike.app.model.NewsBlock;
import com.onelike.app.repository.ArticleImageRepository;
import com.onelike.app.repository.NewsBlockRepository;
import com.onelike.app.repository.NewsRepository;
import com.onelike.app.service.MyproxyService;
import com.onelike.app.service.NewsBlockService;
import com.onelike.app.service.NewsService;
import com.onelike.app.util.SocialMediaPlatforms;
import com.rometools.rome.io.XmlReader;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 5-minute feed job (SearchController.saveNews -> articlesOfAllSocialMedias
 * -> articlesOf, 20 times) must not let ONE bad platform freeze or corrupt the
 * refresh of the others. Over this class's own in-memory H2 database
 * (named after the class), through {@link IsolatedH2Test}.
 *
 * What each case pins, by the code path it forces:
 *
 * - T1 saveNews end to end, no proxies, one unreadable saved feed: true, and
 *   GET /service/news answers all 20 cards in SocialMediaPlatforms.ALL order.
 * - T2..T5 the four reachable saved-feed fallbacks in articlesOf, each with an
 *   unreadable saved feed (and READABLE stored previews next to it, which must
 *   not be served alongside news they do not belong to): no proxy at all,
 *   every proxy inactive, an empty RSS, an RSS that does not parse. The fifth
 *   (unreachable URL) needs URL.openConnection(proxy) to throw, which no input
 *   here can cause.
 * - T6, T7 a reader whose close() throws must not touch the stored feed nor
 *   replace the card already built.
 * - T8 every platform throwing OUTSIDE the parse sites still yields 20 complete
 *   cards, each carrying what was previously fetched for it - its previews
 *   from news_block.linkspreview, NOT from the property-1 News row.
 * - T9 the saved-feed helper on its own.
 * - T10 the success path stores, in the same row as the feed, exactly the
 *   previews it served.
 * - T11 a NULL, garbage or non-array stored preview column reads as [].
 * - T12 stored previews whose url is not in the saved news are dropped.
 * - T13 the four reachable degraded branches serve the stored previews and
 *   the stored `updated`, in Date.toString() form.
 * - T14 two rows for one platform and language cost that platform an empty
 *   card, never the run.
 * - T15 a refresh in which EVERY card has no news (two rows for every
 *   platform) keeps the previous property-1 cards, and GET /service/news
 *   serves them.
 * - T16 the control for T15: one platform with news and nineteen failing is
 *   still stored.
 * - T17 an empty all-socials list keeps the previous property-2 row, while
 *   the cards of the same run are stored.
 *
 * No address other than 127.0.0.1 is ever dialled. The proxies point at a
 * port that was bound and closed a moment earlier, so isOnline's connect is
 * refused locally; the Google News URLConnection is created but never
 * connected, because the XmlReaderOpener seam hands articlesOf a reader over
 * fixed bytes instead. T1, T2, T8 and T14..T17 open no socket at all. T10's feed
 * links are not Google News URLs, so GoogleNewsUrlDecoder skips them without a
 * request, and every one of them is seeded in article_image (one positive, one
 * fresh negative), so ArticleImageCache scans no page.
 *
 * Measured, and the reason the closing reader is picky about its caller: the
 * JDK's SAX parser closes its input itself at end of document, from inside
 * SyndFeedInput.build (via rome's XmlFixerReader.close). A close() that threw
 * unconditionally would therefore make even a valid empty RSS fail to parse,
 * and T6 would silently take T7's path. So it throws only when called directly
 * from SearchController - i.e. from articlesOf's finally - and counts that.
 */
@IsolatedH2Test
class SearchControllerFeedJobTest {

    private static final String LANGUAGE = "English";

    /** Truncated JSON: MODE_PERMISSIVE throws on it, which is what used to escape articlesOf. */
    private static final String UNREADABLE_FEED = "[{\"title\":";

    private static final String EMPTY_RSS = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
            + "<title>t</title><link>http://localhost/</link><description>d</description></channel></rss>";

    /** Well-formed XML that is not a feed, so the XmlReader opens fine and SyndFeedInput.build throws. */
    private static final String NOT_A_FEED = "<?xml version=\"1.0\"?><notafeed/>";

    private static final String GOOD_FEED = "[{\"title\":\"Saved one\",\"url\":\"http://localhost/saved-1\","
            + "\"publishedat\":\"Sat Oct 18 10:00:00 UTC 2026\",\"timeago\":\"1 h\",\"type\":\"X\"},"
            + "{\"title\":\"Saved two\",\"url\":\"http://localhost/saved-2\","
            + "\"publishedat\":\"Sat Oct 18 09:00:00 UTC 2026\",\"timeago\":\"2 h\",\"type\":\"X\"}]";

    private static final String GOOD_PREVIEWS = "[{\"title\":\"Saved one\",\"url\":\"http://localhost/saved-1\","
            + "\"image\":\"http://localhost/saved-1.png\",\"timeago\":\"1 h\"}]";

    /** Previews of the same saved article, but NOT what news_block stores: T8 must not serve these. */
    private static final String PROPERTY_ONE_PREVIEWS = "[{\"title\":\"Saved two\",\"url\":\"http://localhost/saved-2\","
            + "\"image\":\"http://localhost/from-property-1.png\",\"timeago\":\"2 h\"}]";

    /** An RSS with two items whose links are publisher URLs, so the decoder skips them without a request. */
    private static final String FRESH_LINK_1 = "http://localhost/fresh-1";
    private static final String FRESH_LINK_2 = "http://localhost/fresh-2";
    private static final String FRESH_IMAGE_1 = "http://localhost/fresh-1.png";
    private static final String TWO_ITEM_RSS = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
            + "<title>t</title><link>http://localhost/</link><description>d</description>"
            + "<item><title>Fresh one</title><link>" + FRESH_LINK_1 + "</link>"
            + "<pubDate>Fri, 17 Oct 2026 10:00:00 GMT</pubDate></item>"
            + "<item><title>Fresh two</title><link>" + FRESH_LINK_2 + "</link>"
            + "<pubDate>Fri, 17 Oct 2026 09:00:00 GMT</pubDate></item>"
            + "</channel></rss>";

    private static final Set<String> CARD_KEYS = new HashSet<>(Arrays.asList(
            "language", "name", "website", "description", "news", "linkspreview", "last_update"));

    /** A stored `updated` well in the past, so any rewrite of it is visible. */
    private static final long SAVED_AT = 1700000000000L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    NewsBlockRepository newsBlockRepository;

    @Autowired
    NewsRepository newsRepository;

    @Autowired
    NewsService newsService;

    @Autowired
    NewsBlockService newsBlockService;

    @Autowired
    ArticleImageRepository articleImageRepository;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void startCapturingAndClearBlocks() {
        newsBlockRepository.deleteAll(); // this class's own H2 rows only, see IsolatedH2Extension
        articleImageRepository.deleteAll();
        logs = new ListAppender<>();
        logs.start();
        searchControllerLogger().addAppender(logs);
    }

    @AfterEach
    void stopCapturing() {
        searchControllerLogger().detachAppender(logs);
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private static Logger searchControllerLogger() {
        return (Logger) LoggerFactory.getLogger(SearchController.class);
    }

    /** A pool of `count` proxies, every id answering `proxy` (null = the row is missing). */
    static class FakeProxies extends MyproxyService {
        private final Myproxy proxy;
        private final long count;

        FakeProxies(Myproxy proxy, long count) {
            this.proxy = proxy;
            this.count = count;
        }

        @Override
        public long getProxyNumber() {
            return count;
        }

        @Override
        public Myproxy findById(Long id) {
            return proxy;
        }

        @Override
        public Myproxy save(Myproxy myproxy) {
            return myproxy; // never persisted
        }

        @Override
        public Myproxy findByIpAndPort(String ip, Integer port) {
            return null;
        }

        @Override
        public List<Myproxy> findByActive(Boolean active) {
            return new ArrayList<>();
        }
    }

    static class AlwaysInactiveProxy extends Myproxy {
        @Override
        public Boolean getActive() {
            return false;
        }
    }

    static class AlwaysActiveProxy extends Myproxy {
        @Override
        public Boolean getActive() {
            return true;
        }
    }

    /** A port on 127.0.0.1 that was bound and released, so a connect to it is refused locally. */
    private static int closedLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static <P extends Myproxy> P atClosedLocalPort(P proxy) throws IOException {
        proxy.setIp("127.0.0.1");
        proxy.setPort(closedLocalPort());
        return proxy;
    }

    private void useProxies(MyproxyService proxies) {
        SearchController.installStaticDao(proxies, newsService, newsBlockService); // restored by IsolatedH2Extension
    }

    /** For paths that must never read a feed: reaching the opener is itself a failure. */
    private static final SearchController.XmlReaderOpener NO_FEED_READ = connection -> {
        throw new AssertionError("this path must not read a feed");
    };

    /** A reader over fixed bytes; see the class comment for why close() checks its caller. */
    private static SearchController.XmlReaderOpener feed(String xml, boolean closeThrows, AtomicInteger thrownCloses) {
        return connection -> new XmlReader(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            @Override
            public void close() throws IOException {
                StackTraceElement caller = new Throwable().getStackTrace()[1];
                if (closeThrows && SearchController.class.getName().equals(caller.getClassName())) {
                    thrownCloses.incrementAndGet();
                    throw new IOException("close() failed on purpose (test)");
                }
                super.close();
            }
        };
    }

    // ------------------------------------------------------------------
    // Fixtures and reading
    // ------------------------------------------------------------------

    private NewsBlock saveBlock(String platform, String feed) throws Exception {
        return saveBlock(platform, feed, null);
    }

    private NewsBlock saveBlock(String platform, String feed, String linkspreview) throws Exception {
        newsBlockRepository.save(block(platform, feed, linkspreview));
        return newsBlockRepository.findBySocialMediaAndLanguage(platform, LANGUAGE);
    }

    private static NewsBlock block(String platform, String feed, String linkspreview) {
        NewsBlock block = new NewsBlock();
        block.setLanguage(LANGUAGE);
        block.setSocialmedia(platform);
        block.setFeed(feed);
        block.setLinkspreview(linkspreview);
        block.setUpdated(new Date(SAVED_AT));
        return block;
    }

    private static String sha256(String value) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** An article_image row as ArticleImageCache keys it; imageUrl null = a fresh negative. */
    private void seedImage(String pageUrl, String imageUrl) throws Exception {
        articleImageRepository.save(new ArticleImage(sha256(pageUrl), imageUrl));
    }

    private static void assertEmptyPreviews(JSONObject card) {
        assertTrue(card.get("linkspreview") instanceof JSONArray, "linkspreview must be an array");
        assertTrue(((JSONArray) card.get("linkspreview")).isEmpty(), "linkspreview must be empty");
    }

    private static Object parse(String json) throws Exception {
        return new JSONParser(JSONParser.MODE_PERMISSIVE).parse(json);
    }

    private static void assertSevenKeys(JSONObject card) {
        assertEquals(CARD_KEYS, card.keySet(), "a card must carry exactly the 7 keys: " + card.keySet());
    }

    private static void assertEmptyNews(JSONObject card) {
        assertTrue(card.get("news") instanceof JSONArray, "news must be an array");
        assertTrue(((JSONArray) card.get("news")).isEmpty(), "news must be empty");
    }

    /** A card as it reaches the wire: serialised and parsed back. */
    private static JSONObject wire(JSONObject card) throws Exception {
        return (JSONObject) parse(card.toJSONString());
    }

    private boolean logged(String fragment) {
        return logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains(fragment));
    }

    private long loggedCount(String fragment) {
        return logs.list.stream().filter(e -> e.getFormattedMessage().contains(fragment)).count();
    }

    private JSONArray servedCards() throws Exception {
        String body = mockMvc.perform(get("/service/news"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JSONArray cards = (JSONArray) ((JSONObject) parse(body)).get("socialmedias");
        assertNotNull(cards, "GET /service/news should answer socialmedias");
        assertEquals(SocialMediaPlatforms.ALL.size(), cards.size(), "one card per platform, none skipped");
        for (int i = 0; i < cards.size(); i++) {
            JSONObject card = (JSONObject) cards.get(i);
            assertSevenKeys(card);
            assertEquals(SocialMediaPlatforms.ALL.get(i), card.get("name"), "card " + i + " out of order");
        }
        return cards;
    }

    private static JSONObject cardNamed(JSONArray cards, String platform) {
        return (JSONObject) cards.get(SocialMediaPlatforms.ALL.indexOf(platform));
    }

    // ------------------------------------------------------------------
    // Cases
    // ------------------------------------------------------------------

    /**
     * T1: one corrupt saved feed used to make saveNews answer false and store
     * nothing. Another platform has a good saved feed, so the run has a card
     * with news and is stored (a run with none is kept out: see T15).
     */
    @Test
    void t1_oneUnreadableSavedFeedDoesNotStopTheRefresh() throws Exception {
        String bad = SocialMediaPlatforms.ALL.get(3);
        saveBlock(bad, UNREADABLE_FEED);
        saveBlock(SocialMediaPlatforms.ALL.get(0), GOOD_FEED);
        // The context's own, empty proxy pool (installed by IsolatedH2Extension): no socket.

        assertTrue(SearchController.saveNews(LANGUAGE), "saveNews should store the run");

        JSONArray cards = servedCards();
        assertEmptyNews(cardNamed(cards, bad));
        assertEquals(parse(GOOD_FEED), cardNamed(cards, SocialMediaPlatforms.ALL.get(0)).get("news"),
                "the good platform's saved feed was stored with the run");
    }

    /** T2: the no-proxy fallback. */
    @Test
    void t2_noProxyFallbackServesEmptyNewsForAnUnreadableSavedFeed() throws Exception {
        String platform = SocialMediaPlatforms.ALL.get(1);
        saveBlock(platform, UNREADABLE_FEED, GOOD_PREVIEWS);
        useProxies(new FakeProxies(null, 0));

        JSONObject card = wire(SearchController.articlesOf(LANGUAGE, platform, new JSONArray(), NO_FEED_READ));

        assertSevenKeys(card);
        assertEmptyNews(card);
        assertEmptyPreviews(card);
        assertTrue(logged("(no proxy)"), "the no-proxy fallback should have served it");
    }

    /** T3: the every-proxy-inactive fallback. */
    @Test
    void t3_inactiveProxyFallbackServesEmptyNewsForAnUnreadableSavedFeed() throws Exception {
        String platform = SocialMediaPlatforms.ALL.get(2);
        saveBlock(platform, UNREADABLE_FEED, GOOD_PREVIEWS);
        useProxies(new FakeProxies(atClosedLocalPort(new AlwaysInactiveProxy()), 1));

        JSONObject card = wire(SearchController.articlesOf(LANGUAGE, platform, new JSONArray(), NO_FEED_READ));

        assertSevenKeys(card);
        assertEmptyNews(card);
        assertEmptyPreviews(card);
        assertTrue(logged("(all proxies inactive)"), "the inactive-proxy fallback should have served it");
    }

    /**
     * T4: the empty-RSS fallback. Asserted by its log tag as well as by shape,
     * because if THIS site throws, the catch below it re-runs the saved-feed
     * read through the unreadable-RSS fallback and returns the very same card.
     */
    @Test
    void t4_emptyRssFallbackServesEmptyNewsForAnUnreadableSavedFeed() throws Exception {
        String platform = SocialMediaPlatforms.ALL.get(4);
        saveBlock(platform, UNREADABLE_FEED, GOOD_PREVIEWS);
        useProxies(new FakeProxies(atClosedLocalPort(new AlwaysActiveProxy()), 1));

        JSONObject card = wire(SearchController.articlesOf(LANGUAGE, platform, new JSONArray(),
                feed(EMPTY_RSS, false, new AtomicInteger())));

        assertSevenKeys(card);
        assertEmptyNews(card);
        assertEmptyPreviews(card);
        assertTrue(logged("(empty RSS)"), "the empty-RSS fallback should have served it");
        assertFalse(logged("(unreadable RSS)"), "the empty RSS should not have reached the catch");
    }

    /** T5: the unreadable-RSS fallback, in the catch. */
    @Test
    void t5_unreadableRssFallbackServesEmptyNewsForAnUnreadableSavedFeed() throws Exception {
        String platform = SocialMediaPlatforms.ALL.get(5);
        saveBlock(platform, UNREADABLE_FEED, GOOD_PREVIEWS);
        useProxies(new FakeProxies(atClosedLocalPort(new AlwaysActiveProxy()), 1));

        JSONObject card = wire(SearchController.articlesOf(LANGUAGE, platform, new JSONArray(),
                feed(NOT_A_FEED, false, new AtomicInteger())));

        assertSevenKeys(card);
        assertEmptyNews(card);
        assertEmptyPreviews(card);
        assertTrue(logged("(unreadable RSS)"), "the unreadable-RSS fallback should have served it");
    }

    private void assertCloseFailureChangesNothing(String rss) throws Exception {
        String platform = SocialMediaPlatforms.ALL.get(6);
        NewsBlock before = saveBlock(platform, GOOD_FEED);
        useProxies(new FakeProxies(atClosedLocalPort(new AlwaysActiveProxy()), 1));
        AtomicInteger thrownCloses = new AtomicInteger();

        JSONObject card = wire(SearchController.articlesOf(LANGUAGE, platform, new JSONArray(),
                feed(rss, true, thrownCloses)));

        assertEquals(1, thrownCloses.get(), "articlesOf's own close() should have run, and thrown, once");
        NewsBlock after = newsBlockRepository.findBySocialMediaAndLanguage(platform, LANGUAGE);
        assertEquals(before.getFeed(), after.getFeed(), "a failed close must not rewrite the stored feed");
        assertEquals(before.getUpdated().getTime(), after.getUpdated().getTime(),
                "a failed close must not touch `updated`");
        assertSevenKeys(card);
        assertEquals(parse(GOOD_FEED), card.get("news"), "the card must still carry the saved feed");
    }

    /** T6: empty RSS, close() throws. */
    @Test
    void t6_aFailedCloseAfterAnEmptyRssKeepsTheSavedFeedAndTheCard() throws Exception {
        assertCloseFailureChangesNothing(EMPTY_RSS);
    }

    /** T7: RSS that does not parse, close() throws. */
    @Test
    void t7_aFailedCloseAfterAnUnreadableRssKeepsTheSavedFeedAndTheCard() throws Exception {
        assertCloseFailureChangesNothing(NOT_A_FEED);
    }

    /**
     * T8: the per-platform guard. The missing-proxy stub makes articlesOf throw
     * for EVERY platform before it reaches any parse site (isOnline(null) is an
     * NPE), so the run used to answer false. Now each platform gets a fallback
     * card carrying what was previously fetched for it: its saved news, the
     * previews stored next to that news in news_block.linkspreview, and its
     * stored `updated`; and [] for a platform that never had anything saved.
     *
     * The property-1 News row is seeded with DIFFERENT previews for the same
     * platform, so a fallback still reading the previous cards fails here.
     */
    @Test
    void t8_everyPlatformThrowingStillYieldsTwentyCardsWithWhatWasPreviouslyFetched() throws Exception {
        String saved = SocialMediaPlatforms.ALL.get(0);
        String neverSaved = SocialMediaPlatforms.ALL.get(7);
        NewsBlock block = saveBlock(saved, GOOD_FEED, GOOD_PREVIEWS);

        News previous = newsRepository.findByPropertyAndLanguage(1, LANGUAGE);
        assertNotNull(previous, "NewsTableSeeder should have seeded the English property-1 row");
        previous.setFeed("{\"socialmedias\":[{\"name\":\"" + saved + "\",\"linkspreview\":" + PROPERTY_ONE_PREVIEWS + "}]}");
        newsRepository.save(previous);

        useProxies(new FakeProxies(null, 1));

        assertTrue(SearchController.saveNews(LANGUAGE), "saveNews should store the run");
        assertEquals(SocialMediaPlatforms.ALL.size(), loggedCount(": the refresh threw ("),
                "every platform should have thrown outside the parse sites");

        JSONArray cards = servedCards();

        JSONObject savedCard = cardNamed(cards, saved);
        assertEquals(parse(GOOD_FEED), savedCard.get("news"), "the saved feed, not []");
        assertEquals(parse(GOOD_PREVIEWS), savedCard.get("linkspreview"),
                "the previews stored with that feed, not the property-1 row's");
        JSONObject expectedDate = new JSONObject();
        expectedDate.put("d", new Date(block.getUpdated().getTime()));
        assertEquals(((JSONObject) parse(expectedDate.toJSONString())).get("d"), savedCard.get("last_update"),
                "the stored `updated`, serialised like every other card's last_update");

        JSONObject emptyCard = cardNamed(cards, neverSaved);
        assertEmptyNews(emptyCard);
        assertTrue(((JSONArray) emptyCard.get("linkspreview")).isEmpty(), "no previews without saved news");
    }

    /** T9: the helper on its own - every unusable input is an empty array, and a good one passes through. */
    @Test
    void t9_theSavedFeedHelperAnswersAnArrayForEveryInput() throws Exception {
        assertTrue(SearchController.savedFeedOf(null, LANGUAGE, "X", "t9").isEmpty(), "no block");

        NewsBlock block = new NewsBlock();
        block.setFeed(null);
        assertTrue(SearchController.savedFeedOf(block, LANGUAGE, "X", "t9").isEmpty(), "null feed");

        block.setFeed("garbage");
        assertTrue(SearchController.savedFeedOf(block, LANGUAGE, "X", "t9").isEmpty(), "bare text");

        block.setFeed(UNREADABLE_FEED);
        assertTrue(SearchController.savedFeedOf(block, LANGUAGE, "X", "t9").isEmpty(), "does not parse");

        block.setFeed("{}");
        assertTrue(SearchController.savedFeedOf(block, LANGUAGE, "X", "t9").isEmpty(), "an object, not an array");

        block.setFeed(GOOD_FEED);
        JSONArray good = SearchController.savedFeedOf(block, LANGUAGE, "X", "t9");
        assertEquals(parse(GOOD_FEED), good, "a valid array passes through");
        assertEquals(2, good.size());
    }

    /**
     * T10: the success path. The row held stale previews and an old feed; after
     * one refresh the SAME row (still exactly one for the platform) holds the
     * new feed and exactly the previews the card served - here one entry, for
     * the page article_image has a positive for, and none for the page it holds
     * a fresh negative for.
     */
    @Test
    void t10_theSuccessPathStoresThePreviewsItServedInTheFeedsRow() throws Exception {
        String platform = SocialMediaPlatforms.ALL.get(8);
        NewsBlock before = saveBlock(platform, GOOD_FEED, GOOD_PREVIEWS);
        seedImage(FRESH_LINK_1, FRESH_IMAGE_1);
        seedImage(FRESH_LINK_2, null);
        useProxies(new FakeProxies(atClosedLocalPort(new AlwaysActiveProxy()), 1));

        JSONObject card = wire(SearchController.articlesOf(LANGUAGE, platform, new JSONArray(),
                feed(TWO_ITEM_RSS, false, new AtomicInteger())));

        assertSevenKeys(card);
        assertEquals(2, ((JSONArray) card.get("news")).size(), "both fresh items: " + card.get("news"));
        JSONArray previews = (JSONArray) card.get("linkspreview");
        assertEquals(1, previews.size(), "one preview: the cached positive, not the fresh negative: " + previews);
        assertEquals(FRESH_LINK_1, ((JSONObject) previews.get(0)).get("url"));
        assertEquals(FRESH_IMAGE_1, ((JSONObject) previews.get(0)).get("image"));

        List<NewsBlock> rows = new ArrayList<>();
        for (NewsBlock row : newsBlockRepository.findAll()) {
            if (platform.equals(row.getSocialmedia())) {
                rows.add(row);
            }
        }
        assertEquals(1, rows.size(), "still exactly one row for the platform");
        NewsBlock after = rows.get(0);
        assertEquals(before.getId(), after.getId(), "the same row, updated in place");
        assertEquals(card.get("news"), parse(after.getFeed()), "the row holds the feed the card served");
        assertNotNull(after.getLinkspreview(), "the success path must set the preview column");
        assertEquals(previews, parse(after.getLinkspreview()), "and, in that same row, the previews it served");
        assertTrue(after.getUpdated().getTime() > SAVED_AT, "and a fresh `updated`");
    }

    /** T11: every unusable stored preview column reads as [], next to perfectly good saved news. */
    @Test
    void t11_aNullGarbageOrNonArrayPreviewColumnReadsAsEmpty() throws Exception {
        String[] unusable = {null, "garbage", "{\"url\":\"http://localhost/saved-1\"}", "[{\"title\":", "42"};
        for (String stored : unusable) {
            logs.list.clear();
            JSONObject card = wire(SearchController.savedCard(LANGUAGE, "X", block("X", GOOD_FEED, stored), "t11"));
            assertSevenKeys(card);
            assertEquals(parse(GOOD_FEED), card.get("news"), "the news is unaffected by " + stored);
            assertEmptyPreviews(card);
            if (stored == null) {
                // A NULL column is every row stored before the column existed:
                // the expected case, answered by the explicit null check, not
                // a parse failure.
                assertFalse(logged("saved previews do not parse"), "a NULL column is not a parse failure");
                assertFalse(logged("saved previews are not an array"), "a NULL column is not a non-array");
            } else if (stored.equals(UNREADABLE_FEED)) {
                // Control: the WARN the NULL case must not raise does fire for a real parse failure.
                assertTrue(logged("saved previews do not parse"), "truncated JSON should warn that it does not parse");
            }
        }
    }

    /** T12: only previews of articles the saved news still lists are served. */
    @Test
    void t12_storedPreviewsWhoseUrlIsNotInTheSavedNewsAreDropped() throws Exception {
        String stored = "[{\"title\":\"Gone\",\"url\":\"http://localhost/gone\",\"image\":\"http://localhost/g.png\"},"
                + "\"not an object\","
                + "{\"title\":\"No url\",\"image\":\"http://localhost/n.png\"},"
                + GOOD_PREVIEWS.substring(1, GOOD_PREVIEWS.length() - 1) + "]";

        JSONObject card = wire(SearchController.savedCard(LANGUAGE, "X", block("X", GOOD_FEED, stored), "t12"));

        assertEquals(parse(GOOD_PREVIEWS), card.get("linkspreview"), "only the entry for a listed url survives");
    }

    /**
     * T13: the four reachable degraded branches each serve the stored previews
     * and the stored `updated` - as the Date.toString() string, not as the
     * java.sql.Timestamp Hibernate loads ({"nanos":...}) and not as now.
     * Identified by savedCard's own debug tag, so a branch that threw into the
     * next one cannot pass for it.
     */
    @Test
    void t13_theDegradedBranchesServeTheStoredPreviewsAndTheStoredUpdated() throws Exception {
        Logger logger = searchControllerLogger();
        Level level = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            assertDegradedBranch("no proxy", 9, new FakeProxies(null, 0), NO_FEED_READ);
            assertDegradedBranch("all proxies inactive", 10,
                    new FakeProxies(atClosedLocalPort(new AlwaysInactiveProxy()), 1), NO_FEED_READ);
            assertDegradedBranch("empty RSS", 11, new FakeProxies(atClosedLocalPort(new AlwaysActiveProxy()), 1),
                    feed(EMPTY_RSS, false, new AtomicInteger()));
            assertDegradedBranch("unreadable RSS", 12, new FakeProxies(atClosedLocalPort(new AlwaysActiveProxy()), 1),
                    feed(NOT_A_FEED, false, new AtomicInteger()));
        } finally {
            logger.setLevel(level);
        }
    }

    private void assertDegradedBranch(String site, int platformIndex, MyproxyService proxies,
                                      SearchController.XmlReaderOpener opener) throws Exception {
        String platform = SocialMediaPlatforms.ALL.get(platformIndex);
        saveBlock(platform, GOOD_FEED, GOOD_PREVIEWS);
        useProxies(proxies);

        JSONObject card = wire(SearchController.articlesOf(LANGUAGE, platform, new JSONArray(), opener));

        assertTrue(logged(platform + " " + LANGUAGE + ": serving the saved card (" + site + ")"),
                site + ": that branch should have served it");
        assertSevenKeys(card);
        assertEquals(parse(GOOD_FEED), card.get("news"), site + ": the saved news");
        assertEquals(parse(GOOD_PREVIEWS), card.get("linkspreview"), site + ": the stored previews");
        assertEquals(new Date(SAVED_AT).toString(), card.get("last_update"),
                site + ": the stored `updated`, in Date.toString() form");
    }

    /**
     * T14: two English rows for one platform make every lookup of it throw
     * (the query expects one result). That costs the platform an empty card -
     * the per-platform catch, then fallbackCard's guarded lookup - and the run
     * still stores 20 cards, because another platform's card has news.
     */
    @Test
    void t14_twoRowsForOnePlatformStillGiveTwentyCardsAndAnEmptyCard() throws Exception {
        String duplicated = SocialMediaPlatforms.ALL.get(13);
        newsBlockRepository.save(block(duplicated, GOOD_FEED, GOOD_PREVIEWS));
        newsBlockRepository.save(block(duplicated, GOOD_FEED, GOOD_PREVIEWS));
        saveBlock(SocialMediaPlatforms.ALL.get(0), GOOD_FEED);
        // The context's own, empty proxy pool (installed by IsolatedH2Extension): no socket.

        assertTrue(SearchController.saveNews(LANGUAGE), "saveNews should store the run");
        assertTrue(logged(duplicated + " " + LANGUAGE + ": the saved row cannot be read"),
                "the guarded lookup should have been what served it");

        JSONObject card = cardNamed(servedCards(), duplicated);
        assertEmptyNews(card);
        assertEmptyPreviews(card);
    }

    private static final String MARKER_TITLE = "Previous refresh marker";

    /** The news every card of the seeded "previous refresh" carries. */
    private static final String MARKER_FEED = "[{\"title\":\"" + MARKER_TITLE + "\",\"url\":\"http://localhost/marker\","
            + "\"publishedat\":\"Fri Oct 17 10:00:00 UTC 2026\",\"timeago\":\"1 d\",\"type\":\"X\"}]";

    /**
     * Seeds the English property-1 row with a previous refresh of 20 complete
     * cards, each carrying MARKER_FEED, through the production save path (which
     * also evicts the cache GET /service/news reads). Answers the stored row.
     */
    private News seedPreviousCards() throws Exception {
        JSONArray cards = new JSONArray();
        for (String platform : SocialMediaPlatforms.ALL) {
            cards.add(SearchController.savedCard(LANGUAGE, platform, block(platform, MARKER_FEED, null), "seed"));
        }
        JSONObject previous = new JSONObject();
        previous.put("socialmedias", cards);
        News row = newsRepository.findByPropertyAndLanguage(1, LANGUAGE);
        assertNotNull(row, "NewsTableSeeder should have seeded the English property-1 row");
        row.setFeed(previous.toJSONString());
        newsService.save(row, LANGUAGE);
        News stored = newsRepository.findByPropertyAndLanguage(1, LANGUAGE);
        assertTrue(stored.getFeed().contains(MARKER_TITLE), "control: the marker is in the row before the run");
        return stored;
    }

    /** Two rows for a platform make every lookup of it throw, as in T14. */
    private void duplicateRows(String platform) {
        newsBlockRepository.save(block(platform, GOOD_FEED, GOOD_PREVIEWS));
        newsBlockRepository.save(block(platform, GOOD_FEED, GOOD_PREVIEWS));
    }

    /**
     * T15: N-A. Two rows for EVERY platform, so every lookup throws and every
     * card is an empty fallback. At HEAD that throw aborted the run and the
     * previous cards survived; the per-platform guard turned it into 20 empty
     * cards stored over them. Now the property-1 row is left as it was, a WARN
     * names the language, and GET /service/news serves the previous cards.
     */
    @Test
    void t15_aRefreshWithNoNewsOnAnyCardKeepsThePreviousCards() throws Exception {
        for (String platform : SocialMediaPlatforms.ALL) {
            duplicateRows(platform);
        }
        News before = seedPreviousCards();
        // The context's own, empty proxy pool (installed by IsolatedH2Extension): no socket.

        boolean stored = SearchController.saveNews(LANGUAGE);

        // The outcome first, so a wrong design fails on the row, not on a log line or the return value.
        News after = newsRepository.findByPropertyAndLanguage(1, LANGUAGE);
        assertEquals(before.getFeed(), after.getFeed(), "the previous cards must be left as they were");
        assertEquals(before.getUpdated(), after.getUpdated(), "and so must their `updated`");

        JSONArray cards = servedCards();
        for (int i = 0; i < cards.size(); i++) {
            assertEquals(parse(MARKER_FEED), ((JSONObject) cards.get(i)).get("news"),
                    "card " + i + " should be the previous refresh's");
        }

        assertEquals(SocialMediaPlatforms.ALL.size(), loggedCount(": the saved row cannot be read"),
                "every platform's lookup should have thrown");
        assertTrue(logged(LANGUAGE + ": every card in this refresh has no news, keeping the previous cards"),
                "the kept refresh should be logged, naming the language");
        assertFalse(stored, "nothing should have been stored");
    }

    /**
     * T16: the control for T15's guard. Nineteen platforms fail exactly as in
     * T15 and ONE has a readable saved feed: the run has a card with news, so it
     * is stored over the previous cards, exactly as before the guard.
     */
    @Test
    void t16_oneCardWithNewsAmongFailuresIsStillStored() throws Exception {
        String kept = SocialMediaPlatforms.ALL.get(15);
        for (String platform : SocialMediaPlatforms.ALL) {
            if (!platform.equals(kept)) {
                duplicateRows(platform);
            }
        }
        saveBlock(kept, GOOD_FEED, GOOD_PREVIEWS);
        seedPreviousCards();

        boolean stored = SearchController.saveNews(LANGUAGE);

        News after = newsRepository.findByPropertyAndLanguage(1, LANGUAGE);
        assertFalse(after.getFeed().contains(MARKER_TITLE), "the previous cards should have been replaced");
        JSONArray cards = servedCards();
        assertEquals(parse(GOOD_FEED), cardNamed(cards, kept).get("news"), "the one card with news");
        for (String platform : SocialMediaPlatforms.ALL) {
            if (!platform.equals(kept)) {
                assertEmptyNews(cardNamed(cards, platform));
            }
        }

        assertEquals(SocialMediaPlatforms.ALL.size() - 1, loggedCount(": the saved row cannot be read"),
                "every other platform's lookup should have thrown");
        assertFalse(logged("every card in this refresh has no news"), "the guard must not fire");
        assertTrue(stored, "saveNews should store the run");
    }

    /**
     * T17: N-B. Every card here is a saved fallback, so no fresh article
     * reaches the all-socials list and json-smart writes it as {"news":[]} -
     * which the old string guard ("{\"news\" : []}", with spaces) never
     * matched, so the empty list was stored over the previous property-2 row
     * that performSearchNews reads. Now that row is left as it was, while the
     * same run's cards (one has news) are stored.
     */
    @Test
    void t17_anEmptyAllSocialsListKeepsThePreviousOne() throws Exception {
        String withNews = SocialMediaPlatforms.ALL.get(16);
        saveBlock(withNews, GOOD_FEED);
        String previousList = "{\"news\":" + MARKER_FEED + "}";
        News flat = newsRepository.findByPropertyAndLanguage(2, LANGUAGE);
        assertNotNull(flat, "NewsTableSeeder should have seeded the English property-2 row");
        flat.setAllFeedWithoutCategory(previousList);
        newsService.saveEntireFeed(flat);
        News before = newsRepository.findByPropertyAndLanguage(2, LANGUAGE);
        assertEquals(previousList, before.getAllFeedWithoutCategory(), "control: the marker list is stored");

        boolean stored = SearchController.saveNews(LANGUAGE);

        News after = newsRepository.findByPropertyAndLanguage(2, LANGUAGE);
        assertEquals(previousList, after.getAllFeedWithoutCategory(), "the previous all-socials list must be kept");
        assertEquals(before.getUpdated(), after.getUpdated(), "and so must its `updated`");
        assertEquals(parse(GOOD_FEED), cardNamed(servedCards(), withNews).get("news"),
                "control: the same run's cards were stored");

        assertTrue(logged(LANGUAGE + ": the all-socials list of this refresh is empty, keeping the previous all-socials list"),
                "the kept list should be logged, naming the language");
        assertTrue(stored, "the cards should still be stored");
    }
}
