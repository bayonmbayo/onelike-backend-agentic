package com.onelike.app.controller;

import com.jayway.jsonpath.JsonPath;
import com.onelike.app.repository.FilterNewsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The agent gate through the real dispatcher, modelled on
 * FilterNewsControllerAuthTest.
 *
 * The third case is the discriminating one. "No key is rejected" and "a wrong
 * key is rejected" would both pass against an endpoint that rejects
 * everything - including one whose mapping does not exist, since Spring's own
 * 404 is also "not a 200". So one case sends the REAL configured key with a
 * blank name and asserts 400 filternews.name.invalid: that answer can only
 * come from past the key check and from this feature's own error shape, and it
 * proves the accept path is reachable WITHOUT inserting a row.
 *
 * NEVER add .andDo(print()) to these cases. It is the reflex when a MockMvc
 * test fails, and here it would write the real configured key - sent as an
 * X-API-Key header by the third case - into target/surefire-reports/. Read the
 * assertion failure instead; it names the status and the JSON path.
 *
 * The key is read through @Value from the application's own configured
 * onelike.news.filter.apikey (resolved from ONELIKE_NEWS_FILTER_APIKEY via
 * spring-dotenv), exactly as the Console test reads jwt.secret. A key of this
 * test's own choosing would be rejected and the test would pass for the wrong
 * reason, proving nothing. The value is never printed or asserted on.
 *
 * Both transports are covered: the wrong-key case goes through ?apiKey= and
 * the correct-key case through the X-API-Key header. The fourth case covers
 * the precedence between them: an empty or whitespace ?apiKey= must fall back
 * to the header rather than be presented as the key.
 *
 * Every case asserts the row count is unchanged, so the suite still writes
 * nothing to onelike_test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FilterNewsAgentControllerAuthTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FilterNewsRepository filterNewsRepository;

    @Value("${onelike.news.filter.apikey}")
    String apikey;

    private static final String WRONG_KEY = "test-fixture-wrong-key-not-a-real-secret";

    @Test
    void postWithNoKeyIsUnauthorized() throws Exception {
        long before = filterNewsRepository.count();

        mockMvc.perform(post("/service/filternews/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Agent Auth Test Publisher\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors[0]").value("filternews.apikey.invalid"));

        assertEquals(before, filterNewsRepository.count());
    }

    /**
     * Sent as the query parameter, the transport the sibling API's agents
     * already use. A wrong key answers the same code and status as no key at
     * all - the response must not tell a prober that the parameter name landed.
     * The value must stay non-blank: a blank ?apiKey= makes the controller fall
     * back to the absent X-API-Key header, and this case would then pass
     * without testing a wrong key at all.
     */
    @Test
    void postWithAWrongKeyAsQueryParameterIsUnauthorized() throws Exception {
        assertFalse(WRONG_KEY.isBlank(), "WRONG_KEY must be non-blank, or this case tests the header fallback");

        long before = filterNewsRepository.count();

        mockMvc.perform(post("/service/filternews/agent")
                        .param("apiKey", WRONG_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Agent Auth Test Publisher\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors[0]").value("filternews.apikey.invalid"));

        assertEquals(before, filterNewsRepository.count());
    }

    /**
     * The correct key, sent as the header, with a name that cannot be stored.
     * 400 rather than 401 is the whole point: it places the failure after the
     * key check, which is the only way to prove the accept path works without
     * writing a row.
     */
    @Test
    void postWithTheCorrectKeyAsHeaderAndABlankNameIsBadRequest() throws Exception {
        long before = filterNewsRepository.count();

        mockMvc.perform(post("/service/filternews/agent")
                        .header("X-API-Key", apikey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("filternews.name.invalid"));

        assertEquals(before, filterNewsRepository.count());
    }

    /**
     * The controller's precedence rule - ?apiKey= wins only when it is
     * non-blank - pinned from the blank side. An empty and a whitespace-only
     * ?apiKey= are each sent alongside the REAL key in X-API-Key and a blank
     * name: both must reach the name check (400 filternews.name.invalid), which
     * proves the blank parameter fell back to the header instead of being
     * presented as the key (which would answer 401). Two requests, one test,
     * each assertion labelled with the request it belongs to. assertAll runs
     * both even when the first fails, so a failure names every request that
     * broke rather than only the first.
     */
    @Test
    void blankApiKeyQueryParameterFallsBackToTheHeader() {
        assertAll(
                () -> assertBlankQueryKeyFallsBack("empty ?apiKey=", ""),
                () -> assertBlankQueryKeyFallsBack("whitespace ?apiKey=", "   "));
    }

    private void assertBlankQueryKeyFallsBack(String label, String queryKey) throws Exception {
        long before = filterNewsRepository.count();

        MvcResult result = mockMvc.perform(post("/service/filternews/agent")
                        .queryParam("apiKey", queryKey)
                        .header("X-API-Key", apikey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus(), label + ": status");
        assertEquals("filternews.name.invalid",
                JsonPath.read(result.getResponse().getContentAsString(), "$.errors[0]"), label + ": errors[0]");
        assertEquals(before, filterNewsRepository.count(), label + ": row count");
    }
}
