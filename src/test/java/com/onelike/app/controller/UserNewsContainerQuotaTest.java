package com.onelike.app.controller;

import com.onelike.app.model.User;
import com.onelike.app.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
 * The harness - datasource, create-drop, placeholder fillers, and the two
 * refusals (the DataSource must be this class's own H2 database, and the
 * proxy pool SearchController will use must be empty) - is shared, in
 * {@link IsolatedH2Test} and {@link IsolatedH2Extension}. This class declares
 * only the two numbers under test; its database name is derived from the
 * class name by {@link IsolatedH2ContextCustomizerFactory}.
 *
 * What makes the create path cheap enough to drive from a test at all: a fresh
 * schema has an empty `myproxy` table, so SearchController.articlesOf finds
 * getProxyNumber() == 0, never enters its liveness loop, and returns the
 * no-proxy answer without opening a single socket. No proxy budget is spent and
 * no outbound request is made - a create here is a few INSERTs.
 */
@IsolatedH2Test
@TestPropertySource(properties = {
        // The two numbers under test, pinned so the cases do not depend on
        // application.properties staying as it is today.
        "onelike.containers.default-themes-per-user=1",
        "onelike.containers.max-themes-per-user=10"
})
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
