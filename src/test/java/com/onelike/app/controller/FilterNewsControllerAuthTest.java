package com.onelike.app.controller;

import com.onelike.app.repository.FilterNewsRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gate itself, through the real dispatcher: these two cases are the ones a
 * refactor is most likely to break silently, because a caller who is turned
 * away cannot tell a 401 from a 400 without reading errors[0].
 *
 * Both cases are rejected by the first statement of the controller method, so
 * neither reaches the repository. The row count is asserted before and after to
 * keep that honest - the test writes nothing to onelike_test.
 *
 * The USER token is signed with the application's own configured jwt.secret
 * (resolved from JWT_SECRET via spring-dotenv), not with a secret of this
 * test's choosing: a wrong secret would answer 401 and the test would pass for
 * the wrong reason, proving nothing about the role check.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FilterNewsControllerAuthTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FilterNewsRepository filterNewsRepository;

    @Value("${jwt.secret}")
    String secret;

    @Test
    void postWithNoAuthorizationHeaderIsUnauthorized() throws Exception {
        long before = filterNewsRepository.count();

        mockMvc.perform(post("/service/filternews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Console Auth Test Publisher\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors[0]").value("filternews.unauthorized"));

        assertEquals(before, filterNewsRepository.count());
    }

    @Test
    void postWithAUserRoleTokenIsForbidden() throws Exception {
        long before = filterNewsRepository.count();

        mockMvc.perform(post("/service/filternews")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Console Auth Test Publisher\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0]").value("filternews.forbidden"));

        assertEquals(before, filterNewsRepository.count());
    }

    private String userToken() {
        Date issuedAt = new Date();
        Date expiration = new Date(issuedAt.getTime() + 3600_000L);

        Map<String, Object> claims = new HashMap<>();
        claims.put("role", "USER");

        return Jwts.builder()
                .claims(claims)
                .subject("user@example.com")
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes()))
                .compact();
    }
}
