package com.onelike.app.controller;

import com.onelike.app.model.User;
import com.onelike.app.repository.UserRepository;
import com.onelike.app.service.MyproxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-user theme allowance through the real dispatcher, on an in-memory H2
 * schema Hibernate builds from the entities.
 *
 * Why H2 and not onelike_test: these cases have to WRITE - a create, a second
 * create, a delete - and they have to start from a user who holds no themes.
 * Against a shared schema that is either a fixture to clean up or a test that
 * depends on what the last run left behind. Here the schema is created and
 * dropped with the context and nothing outside this JVM is touched, so no
 * UPDATE is ever issued against onelike or onelike_test.
 *
 * Three details of the URL are load-bearing:
 *
 * - the database NAME is this class's own ("themeallowance"). An in-memory H2
 *   name is a global handle within the JVM, so reusing another test class's
 *   name would share its rows and make the two suites order-dependent.
 * - DB_CLOSE_DELAY is deliberately NOT set. Left at the default the database
 *   dies with the last connection, so nothing survives this class; with
 *   DB_CLOSE_DELAY=-1 it would outlive the context and leak into whatever ran
 *   next.
 * - NON_KEYWORDS=USER is required, not cosmetic: USER is a reserved word in
 *   H2 2.x, and this feature's identity table is called exactly `user`, so
 *   without it Hibernate's CREATE TABLE fails at boot and every case here
 *   errors for a reason that has nothing to do with quotas.
 *
 * What makes the create path cheap enough to drive from a test at all: a fresh
 * schema has an empty `myproxy` table, so SearchController.articlesOf finds
 * getProxyNumber() == 0, never enters its liveness loop, and returns the
 * no-proxy answer without opening a single socket. No proxy budget is spent and
 * no outbound request is made - a create here is a few INSERTs.
 *
 * The secrets below are this class's own invention and are never asserted on.
 * They exist only so the context can satisfy the mandatory ${...} placeholders
 * in application.properties with no .env file present; nothing in these cases
 * reads a real configured credential.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:themeallowance;MODE=MySQL;NON_KEYWORDS=USER",
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
        // The two numbers under test, pinned so the cases do not depend on
        // application.properties staying as it is today.
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
class UserNewsContainerQuotaTest {

    private static final String UUID_NULL_QUOTA = "11111111-1111-1111-1111-111111111111";
    private static final String UUID_QUOTA_THREE = "22222222-2222-2222-2222-222222222222";
    private static final String UUID_DELETER = "33333333-3333-3333-3333-333333333333";

    private static final String EMAIL_NULL_QUOTA = "nullquota@example.invalid";
    private static final String EMAIL_QUOTA_THREE = "quotathree@example.invalid";
    private static final String EMAIL_DELETER = "deleter@example.invalid";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    MyproxyService contextProxyService;

    /**
     * A refusal, not a warning, because the symptom is remote.
     *
     * SearchController holds its MyproxyService, NewsService and NewsBlockService
     * in PRIVATE STATIC fields, assigned by a @PostConstruct on the instance
     * (initStaticDao, SearchController:129-135), because articlesOf is static.
     * Statics are per CLASS LOADER, not per Spring context, and surefire runs
     * every test class in one JVM: whichever test context was CREATED last owns
     * those three fields, regardless of which context is handling the request.
     * So a create issued here can be served by the MySQL-backed beans of the
     * onelike_test context cached alongside this one.
     *
     * That is harmless exactly as long as the proxy pool articlesOf consults is
     * empty: getProxyNumber() == 0 skips the liveness loop, myproxyTmp stays
     * null, and the method returns the no-proxy answer having issued nothing but
     * SELECTs. With a NON-empty pool the same create would open real sockets to
     * real proxies and could save a news_block row - into whichever schema those
     * static beans point at. The symptom would be proxy spend and a stray row in
     * onelike_test, neither of which anybody would trace back to a test about
     * quotas.
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
                        + " (-Dtest=UserNewsContainerQuotaTest) or clear the pool of the schema they point at.");
    }

    private String createBody(String uuid, String email, String title) {
        return "{\"uuid\":\"" + uuid + "\",\"firstname\":\"Test\",\"name\":\"Case\",\"email\":\"" + email
                + "\",\"displayname\":\"Test Case\",\"title\":\"" + title + "\",\"language\":\"English\"}";
    }

    private String deleteBody(String uuid, String email, long id) {
        return "{\"uuid\":\"" + uuid + "\",\"firstname\":\"Test\",\"name\":\"Case\",\"email\":\"" + email
                + "\",\"displayname\":\"Test Case\",\"id\":" + id + "}";
    }

    /**
     * Raw SQL is the admin's interface in production; here the same column is
     * set through the repository, because the thing under test is what the
     * SERVICE does with a stored value, not how it got there. Only this
     * class's own H2 rows are ever touched.
     */
    private void setQuota(String email, Integer quota) {
        User user = userRepository.findUserByEmail(email);
        assertNotNull(user, "the create should have upserted " + email);
        user.setThemequota(quota);
        userRepository.save(user);
    }

    /**
     * The default allowance, end to end: the first theme is created and reports
     * used:1 allowed:1, and the second is refused with the 409 the contract
     * pins. The first create is half the assertion - without it a 409 on the
     * second proves nothing, since an endpoint that refuses everything would
     * also "fail the second".
     */
    @Test
    void aUserWithNoQuotaGetsOneThemeAndIsConflictedOnTheSecond() throws Exception {
        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_NULL_QUOTA, EMAIL_NULL_QUOTA, "Quota Alpha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.themes.used").value(1))
                .andExpect(jsonPath("$.themes.allowed").value(1));

        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_NULL_QUOTA, EMAIL_NULL_QUOTA, "Quota Beta")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0]").value("container.limit.reached"));
    }

    /**
     * The column doing its job: with themequota = 3 the second and third create
     * succeed where the previous case was refused on the second, and every
     * answer reports allowed:3. The quota is set after the first create because
     * the create path is what upserts the user - there is no row to update
     * before it.
     */
    @Test
    void aQuotaOfThreeAllowsTheSecondAndThirdTheme() throws Exception {
        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_QUOTA_THREE, EMAIL_QUOTA_THREE, "Three Alpha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));

        setQuota(EMAIL_QUOTA_THREE, 3);

        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_QUOTA_THREE, EMAIL_QUOTA_THREE, "Three Beta")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.themes.used").value(2))
                .andExpect(jsonPath("$.themes.allowed").value(3));

        mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_QUOTA_THREE, EMAIL_QUOTA_THREE, "Three Gamma")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.themes.used").value(3))
                .andExpect(jsonPath("$.themes.allowed").value(3));
    }

    /**
     * The themes object on the DELETE response. This is the part a future
     * refactor would quietly drop, because it looks like symmetry: the client
     * patches its list locally on a successful delete and does not re-fetch, so
     * without this its used stays stale-high and its Add button stays disabled
     * at the exact moment the user has made room.
     */
    @Test
    void deleteAnswersThePostDeleteThemesObject() throws Exception {
        String created = mockMvc.perform(post("/service/container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID_DELETER, EMAIL_DELETER, "Delete Me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themes.used").value(1))
                .andReturn().getResponse().getContentAsString();

        long id = idOf(created);

        mockMvc.perform(post("/service/container/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteBody(UUID_DELETER, EMAIL_DELETER, id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.themes.used").value(0))
                .andExpect(jsonPath("$.themes.allowed").value(1));
    }

    /**
     * The branch with no user row at all. It is the one input that could NPE
     * rather than answer a wrong number, and the contract requires themes to be
     * present here too - a client reads it as copy material for a brand-new
     * user.
     */
    @Test
    void anUnknownUuidStillReadsAThemesObject() throws Exception {
        mockMvc.perform(get("/service/mycontainers")
                        .param("uuid", "99999999-9999-9999-9999-999999999999")
                        .param("language", "English"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(false))
                .andExpect(jsonPath("$.themes.used").value(0))
                .andExpect(jsonPath("$.themes.allowed").value(1));
    }

    /**
     * The created container's id, read off the create response without pulling
     * in a JSON library the rest of the test tree does not use. The body is
     * hand-built by the controller, so "id": is unambiguous: the only other
     * numeric keys on a theme are position and the ones inside news/
     * linkspreview, and neither is spelled "id".
     */
    private long idOf(String body) {
        int at = body.indexOf("\"id\":");
        assertTrue(at >= 0, "the create response should carry an id: " + body);
        int from = at + "\"id\":".length();
        int to = from;
        while (to < body.length() && (Character.isDigit(body.charAt(to)) || body.charAt(to) == ' ')) {
            to++;
        }
        return Long.parseLong(body.substring(from, to).trim());
    }
}
