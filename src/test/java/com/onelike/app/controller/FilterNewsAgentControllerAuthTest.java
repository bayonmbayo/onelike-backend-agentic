package com.onelike.app.controller;

import com.onelike.app.repository.FilterNewsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * the correct-key case through the X-API-Key header.
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
     */
    @Test
    void postWithAWrongKeyAsQueryParameterIsUnauthorized() throws Exception {
        long before = filterNewsRepository.count();

        mockMvc.perform(post("/service/filternews/agent")
                        .param("apiKey", "bm90LXRoZS1yaWdodC1rZXktYXQtYWxsLW5vdC1jbG9zZQ")
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
}
