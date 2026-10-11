package com.onelike.app.support;

import com.onelike.app.exception.FilterNewsException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * No Spring context and no database: ConsoleToken takes its secret through the
 * constructor precisely so the verification rules can be tested against a
 * secret this class mints its own tokens with. Nothing here touches
 * onelike_test.
 *
 * The secret is 96 characters on purpose. Keys.hmacShaKeyFor picks the strongest
 * HMAC algorithm the key length allows, so a secret longer than 64 bytes gives
 * HS512 - the same algorithm the real issuer's 94-byte secret selects. A short
 * test secret would exercise HS256 and prove nothing about the live token.
 */
class ConsoleTokenTest {

    private static final String SECRET = "test-console-secret-test-console-secret-test-console-secret-test-console-secret-test-cons-96c";
    private static final String OTHER_SECRET = "other-console-secret-other-console-secret-other-console-secret-other-console-secret-othe-96c";

    private final ConsoleToken consoleToken = new ConsoleToken(SECRET);

    @Test
    void acceptsAnAdminToken() throws FilterNewsException {
        String token = token(SECRET, "ADMIN", "admin@example.com", 3600);

        assertEquals("admin@example.com", consoleToken.requireAdminOrModerator("Bearer " + token));
    }

    /**
     * MODERATOR is treated identically to ADMIN on all four endpoints,
     * including the hard delete. That was a decision, not an oversight.
     */
    @Test
    void acceptsAModeratorToken() throws FilterNewsException {
        String token = token(SECRET, "MODERATOR", "mod@example.com", 3600);

        assertEquals("mod@example.com", consoleToken.requireAdminOrModerator("Bearer " + token));
    }

    /**
     * A correctly signed, unexpired token from a real signed-in visitor. The
     * signature is fine, so this has to come back forbidden and not
     * unauthorized: the client shows the same remedy for both, but the codes
     * are distinct in the contract.
     */
    @Test
    void rejectsAUserTokenAsForbidden() {
        String token = token(SECRET, "USER", "user@example.com", 3600);

        FilterNewsException e = assertThrows(FilterNewsException.class,
                () -> consoleToken.requireAdminOrModerator("Bearer " + token));

        assertEquals("filternews.forbidden", e.getMessage());
    }

    /**
     * The live deployment hazard: the token is minted by communo.onelike.app and
     * verified by api.onelike.app, so a JWT_SECRET that differs between the two
     * hosts looks exactly like this.
     */
    @Test
    void rejectsATokenSignedWithAnotherSecret() {
        String token = token(OTHER_SECRET, "ADMIN", "admin@example.com", 3600);

        FilterNewsException e = assertThrows(FilterNewsException.class,
                () -> consoleToken.requireAdminOrModerator("Bearer " + token));

        assertEquals("filternews.unauthorized", e.getMessage());
    }

    /**
     * Expiry is checked by the same parseSignedClaims call as the signature -
     * jjwt throws ExpiredJwtException, which is a JwtException - so there is no
     * separate expiry branch that could be dropped without a test noticing.
     */
    @Test
    void rejectsAnExpiredAdminToken() {
        String token = token(SECRET, "ADMIN", "admin@example.com", -3600);

        FilterNewsException e = assertThrows(FilterNewsException.class,
                () -> consoleToken.requireAdminOrModerator("Bearer " + token));

        assertEquals("filternews.unauthorized", e.getMessage());
    }

    @Test
    void rejectsAMissingHeader() {
        FilterNewsException e = assertThrows(FilterNewsException.class,
                () -> consoleToken.requireAdminOrModerator(null));

        assertEquals("filternews.unauthorized", e.getMessage());
    }

    /**
     * A bare token with no "Bearer " prefix must not be accepted, otherwise the
     * header shape the frontend's authHeaderWithJSON() sends stops being the
     * only accepted one.
     */
    @Test
    void rejectsAHeaderWithoutTheBearerPrefix() {
        String token = token(SECRET, "ADMIN", "admin@example.com", 3600);

        FilterNewsException e = assertThrows(FilterNewsException.class,
                () -> consoleToken.requireAdminOrModerator(token));

        assertEquals("filternews.unauthorized", e.getMessage());
    }

    @Test
    void rejectsGarbageInsteadOfAToken() {
        FilterNewsException e = assertThrows(FilterNewsException.class,
                () -> consoleToken.requireAdminOrModerator("Bearer not-a-jwt"));

        assertEquals("filternews.unauthorized", e.getMessage());
    }

    /**
     * Same claim set as the issuer mints: exactly {role, sub, iat, exp}, with
     * role the Role enum serialised by name and sub the email.
     */
    private String token(String secret, String role, String subject, long secondsFromNow) {
        Date issuedAt = new Date();
        Date expiration = new Date(issuedAt.getTime() + secondsFromNow * 1000);

        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes()))
                .compact();
    }
}
