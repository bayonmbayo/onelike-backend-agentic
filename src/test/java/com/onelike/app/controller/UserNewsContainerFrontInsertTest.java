package com.onelike.app.controller;

import com.onelike.app.model.User;
import com.onelike.app.repository.UserRepository;
import com.onelike.app.service.MyproxyService;
import com.onelike.app.util.SocialMediaPlatforms;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where a newly created theme lands, through the real dispatcher, on an
 * in-memory H2 schema Hibernate builds from the entities.
 *
 * The rule under test: a created theme goes to index 0, ahead of all 20
 * platforms, AT CREATE TIME ONLY. A saved order wins permanently.
 *
 * Every case asserts on the READ-BACK of GET /service/mycontainers, not only on
 * the create echo. The echo's position comes from wirePosition, which walks the
 * stored list with the isRendered predicate; the listing builds its indices in
 * containersJson. They are meant to agree, but they are two code paths, and the
 * one the client actually renders from is the listing.
 *
 * One scenario per method, deliberately: these cases share no fixture and each
 * uses its own uuid/email, so one failure cannot mask the others and the class
 * does not depend on method order.
 *
 * Harness copied from UserNewsContainerQuotaTest, including the three
 * load-bearing details of the URL:
 *
 * - the database NAME is this class's own ("frontinsert"). An in-memory H2 name
 *   is a global handle within the JVM, so reusing another test class's name
 *   would share its rows and make the two suites order-dependent.
 * - DB_CLOSE_DELAY is deliberately NOT set. Left at the default the database
 *   dies with the last connection, so nothing survives this class; with
 *   DB_CLOSE_DELAY=-1 it would outlive the context and leak into whatever ran
 *   next.
 * - NON_KEYWORDS=USER is required, not cosmetic: USER is a reserved word in
 *   H2 2.x, and this feature's identity table is called exactly `user`, so
 *   without it Hibernate's CREATE TABLE fails at boot and every case here
 *   errors for a reason that looks like an unrelated context problem.
 *
 * What makes the create path cheap enough to drive from a test at all: a fresh
 * schema has an empty `myproxy` table, so SearchController.articlesOf finds
 * getProxyNumber() == 0, never enters its liveness loop, and returns the
 * no-proxy answer without opening a single socket.
 *
 * The secrets below are this class's own invention and are never asserted on.
 * They exist only so the context can satisfy the mandatory ${...} placeholders
 * in application.properties with no .env file present; nothing in these cases
 * reads a real configured credential.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:frontinsert;MODE=MySQL;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // Every scheduled job off: nothing here may spend proxy budget, fetch a
        // feed or purge a cache row.
        "onelike.database.searchnews=false",
        "onelike.database.proxies.enabled=false",
        "onelike.cleanup.enabled=false",
        "onelike.containers.refresh.enabled=false",
        // Pinned so these cases do not depend on application.properties staying
        // as it is today. The default of 1 is raised per case through the
        // themequota column, exactly as an admin would.
        "onelike.containers.default-themes-per-user=1",
        "onelike.containers.max-themes-per-user=10",
        // Placeholder fillers, of this class's own choosing. Not credentials.
        "jwt.secret=dGhpcy1pcy1ub3QtdGhlLXJlYWwtc2VjcmV0LWl0LWlzLWEtdGVzdC1maWxsZXItbG9uZy1lbm91Z2gtZm9yLWhzNTEy",
        "onelike.news.filter.apikey=bm90LXRoZS1yZWFsLWFnZW50LWtleS1qdXN0LWEtZmlsbGVy",
        "onelike.hibp.api-key=not-a-real-key",
        "onelike.webshare.api-key=not-a-real-key",
        "spring.mail.password=not-a-real-password",
        "file.upload-dir=target/test-uploads"
})
@AutoConfigureMockMvc
class UserNewsContainerFrontInsertTest {

    private static final String UUID_FIRST = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String UUID_SECOND = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String UUID_REORDERED = "aaaaaaaa-0000-0000-0000-000000000003";
    private static final String UUID_DUPLICATE = "aaaaaaaa-0000-0000-0000-000000000004";

    private static final String EMAIL_FIRST = "frontinsert-first@example.invalid";
    private static final String EMAIL_SECOND = "frontinsert-second@example.invalid";
    private static final String EMAIL_REORDERED = "frontinsert-reordered@example.invalid";
    private static final String EMAIL_DUPLICATE = "frontinsert-duplicate@example.invalid";

    /** The first built-in, i.e. what used to be containers[0] before this cycle. */
    private static final String FIRST_PLATFORM = SocialMediaPlatforms.ALL.get(0);

    /** 20 today. Read from the list rather than written out, so a 21st platform does not break these cases. */
    private static final int PLATFORMS = SocialMediaPlatforms.ALL.size();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    MyproxyService contextProxyService;

    /**
     * A refusal, not a warning, because the symptom is remote. Copied verbatim
     * in intent from UserNewsContainerQuotaTest, and it matters at least as
     * much here: a NEW @SpringBootTest class with its own datasource is exactly
     * the configuration that can inherit another context's MySQL-backed
     * statics.
     *
     * SearchController holds its MyproxyService, NewsService and NewsBlockService
     * in PRIVATE STATIC fields, assigned by a @PostConstruct on the instance
     * (initStaticDao), because articlesOf is static. Statics are per CLASS
     * LOADER, not per Spring context, and surefire runs every test class in one
     * JVM: whichever test context was CREATED last owns those three fields,
     * regardless of which context is handling the request. So a create issued
     * here can be served by the MySQL-backed beans of the onelike_test context
     * cached alongside this one.
     *
     * That is harmless exactly as long as the proxy pool articlesOf consults is
     * empty: getProxyNumber() == 0 skips the liveness loop, myproxyTmp stays
     * null, and the method returns the no-proxy answer having issued nothing but
     * SELECTs. With a NON-empty pool the same create would open real sockets to
     * real proxies and could save a news_block row - into whichever schema those
     * static beans point at. The symptom would be proxy spend and a stray row in
     * onelike_test, neither of which anybody would trace back to a test about
     * card order.
     *
     * So this reads the static field the create path will actually use and
     * refuses to run if it can see a proxy, rather than documenting the
     * assumption and hoping. Reading it is a SELECT and writes nothing.
     */
    @BeforeEach
    void refuseToRunIfTheCreatePathCouldReachAProxy() {
        MyproxyService staticService =
                (MyproxyService) ReflectionTestUtils.getField(SearchController.class, "myproxyService");
        assertNotNull(staticService, "SearchController.myproxyService should have been assigned by initStaticDao()");

        long proxies = staticService.getProxyNumber();
        assertEquals(0L, proxies,
                "REFUSING to run: the static MyproxyService the create path uses can see " + proxies
                        + " proxy/proxies, so a create here would spend real proxy budget and could write a"
                        + " news_block row. The static beans "
                        + (staticService == contextProxyService ? "DO" : "do NOT")
                        + " belong to this test's own H2 context. Run this class alone"
                        + " (-Dtest=UserNewsContainerFrontInsertTest) or clear the pool of the schema they point at.");
    }

    private String createBody(String uuid, String email, String title) {
        return "{\"uuid\":\"" + uuid + "\",\"firstname\":\"Test\",\"name\":\"Case\",\"email\":\"" + email
                + "\",\"displayname\":\"Test Case\",\"title\":\"" + title + "\",\"language\":\"English\"}";
    }

    /**
     * The 20 platforms in their default order followed by the given themes, i.e.
     * exactly the "put my own cards at the end" order a user would drag. Each
     * element carries only kind and name, as the contract requires.
     */
    private String orderBody(String uuid, String email, String... themesLast) {
        StringBuilder body = new StringBuilder();
        body.append("{\"uuid\":\"").append(uuid).append("\",\"firstname\":\"Test\",\"name\":\"Case\",\"email\":\"")
                .append(email).append("\",\"displayname\":\"Test Case\",\"language\":\"English\",\"containers\":[");
        for (int i = 0; i < PLATFORMS; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"kind\":\"PLATFORM\",\"name\":\"").append(SocialMediaPlatforms.ALL.get(i)).append("\"}");
        }
        for (String theme : themesLast) {
            body.append(",{\"kind\":\"THEME\",\"name\":\"").append(theme).append("\"}");
        }
        body.append("]}");
        return body.toString();
    }

    /**
     * Raw SQL is the admin's interface in production; here the same column is
     * set through the repository, because the thing under test is what the
     * SERVICE does with a stored value, not how it got there. Only this class's
     * own H2 rows are ever touched.
     */
    private void setQuota(String email, Integer quota) {
        User user = userRepository.findUserByEmail(email);
        assertNotNull(user, "the create should have upserted " + email);
        user.setThemequota(quota);
        userRepository.save(user);
    }

    private ResultActions listFor(String uuid) throws Exception {
        return mockMvc.perform(get("/service/mycontainers")
                        .param("uuid", uuid)
                        .param("language", "English"))
                .andExpect(status().isOk());
    }

    /**
     * The case the seedDefaults re-read inside createContainer exists for, and
     * the most common path through the endpoint there is: a user who has never
     * written anything creates their first theme.
     *
     * Without that re-read the new row and Twitter are both committed at
     * position 0, findByUserOrderByPositionAsc orders by position alone, and the
     * tie is unspecified - so this case would be NON-DETERMINISTIC rather than
     * failing, which is why it is asserted at both index 0 and index 1.
     */
    @Test
    void theFirstThemeOfANeverSeededUserLandsAheadOfEveryPlatform() throws Exception {
        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_FIRST, EMAIL_FIRST, "Front Alpha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.container.position").value(0));

        listFor(UUID_FIRST)
                .andExpect(jsonPath("$.seeded").value(true))
                .andExpect(jsonPath("$.containers.length()").value(PLATFORMS + 1))
                .andExpect(jsonPath("$.containers[0].kind").value("THEME"))
                .andExpect(jsonPath("$.containers[0].name").value("Front Alpha"))
                .andExpect(jsonPath("$.containers[1].kind").value("PLATFORM"))
                .andExpect(jsonPath("$.containers[1].name").value(FIRST_PLATFORM));
    }

    /**
     * Newest-first, which is not a second rule but a consequence of the first:
     * the second theme front-inserts ahead of the first one as well as ahead of
     * the platforms.
     *
     * The quota is raised after the first create because the create path is what
     * upserts the user - there is no row to update before it.
     */
    @Test
    void aSecondThemeLandsAheadOfTheFirstOne() throws Exception {
        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_SECOND, EMAIL_SECOND, "Second Alpha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));

        setQuota(EMAIL_SECOND, 3);

        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_SECOND, EMAIL_SECOND, "Second Beta")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.container.position").value(0));

        listFor(UUID_SECOND)
                .andExpect(jsonPath("$.containers.length()").value(PLATFORMS + 2))
                .andExpect(jsonPath("$.containers[0].name").value("Second Beta"))
                .andExpect(jsonPath("$.containers[1].name").value("Second Alpha"))
                .andExpect(jsonPath("$.containers[2].name").value(FIRST_PLATFORM));
    }

    /**
     * THE CONSTRAINT ITSELF, and the only case here that distinguishes a
     * write-time implementation from a read-time sort.
     *
     * A user creates a theme (which front-inserts it), then drags it to the very
     * end and saves that order, then creates a second theme. The saved order
     * must be untouched for the first theme - it stays last, behind all 20
     * platforms - while the new one is at index 0.
     *
     * A read-time sort would report the first theme at index 1, newest-first,
     * silently overriding the order the user saved. Nothing else in this class
     * would notice: cases 1 and 2 pass under either implementation.
     */
    @Test
    void aSavedOrderIsNeverReAssertedWhenANewThemeFrontInserts() throws Exception {
        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_REORDERED, EMAIL_REORDERED, "Order Alpha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.container.position").value(0));

        setQuota(EMAIL_REORDERED, 3);

        // The user drags their own card to the end and saves.
        mockMvc.perform(post("/service/containers/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(UUID_REORDERED, EMAIL_REORDERED, "Order Alpha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordered").value(true))
                .andExpect(jsonPath("$.containers[0].name").value(FIRST_PLATFORM))
                .andExpect(jsonPath("$.containers[" + PLATFORMS + "].name").value("Order Alpha"));

        listFor(UUID_REORDERED)
                .andExpect(jsonPath("$.containers[0].name").value(FIRST_PLATFORM))
                .andExpect(jsonPath("$.containers[" + PLATFORMS + "].name").value("Order Alpha"));

        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_REORDERED, EMAIL_REORDERED, "Order Beta")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.container.position").value(0));

        listFor(UUID_REORDERED)
                .andExpect(jsonPath("$.containers.length()").value(PLATFORMS + 2))
                // The new theme, at the front.
                .andExpect(jsonPath("$.containers[0].kind").value("THEME"))
                .andExpect(jsonPath("$.containers[0].name").value("Order Beta"))
                // Everything the user saved, shifted by exactly one and
                // otherwise untouched: the platforms in their saved order...
                .andExpect(jsonPath("$.containers[1].name").value(FIRST_PLATFORM))
                .andExpect(jsonPath("$.containers[2].name").value(SocialMediaPlatforms.ALL.get(1)))
                // ...and the first theme still LAST, where the user put it.
                .andExpect(jsonPath("$.containers[" + (PLATFORMS + 1) + "].kind").value("THEME"))
                .andExpect(jsonPath("$.containers[" + (PLATFORMS + 1) + "].name").value("Order Alpha"));
    }

    /**
     * created:false moves nothing. The duplicate is sent AFTER the theme has
     * been dragged to the end, so "its existing position" is a position the
     * front-insert would have to actively break - asserting index 0 instead
     * would pass under a buggy re-insert.
     */
    @Test
    void aDuplicateTitleAnswersTheExistingContainerAtItsExistingPosition() throws Exception {
        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_DUPLICATE, EMAIL_DUPLICATE, "Dup Alpha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));

        mockMvc.perform(post("/service/containers/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(UUID_DUPLICATE, EMAIL_DUPLICATE, "Dup Alpha")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_DUPLICATE, EMAIL_DUPLICATE, "Dup Alpha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.container.name").value("Dup Alpha"))
                .andExpect(jsonPath("$.container.position").value(PLATFORMS));

        listFor(UUID_DUPLICATE)
                .andExpect(jsonPath("$.containers.length()").value(PLATFORMS + 1))
                .andExpect(jsonPath("$.containers[0].name").value(FIRST_PLATFORM))
                .andExpect(jsonPath("$.containers[" + PLATFORMS + "].name").value("Dup Alpha"));
    }
}
