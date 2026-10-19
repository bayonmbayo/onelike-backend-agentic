package com.onelike.app.support;

import com.onelike.app.exception.FilterNewsException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No Spring context and no database: ConsoleToken takes its secret through the
 * constructor precisely so the verification rules can be tested against a
 * secret this class mints its own tokens with. Nothing here touches
 * onelike_test.
 *
 * The secret is at least 64 bytes on purpose. Keys.hmacShaKeyFor picks the strongest
 * HMAC algorithm the key length allows, so a secret of 64 bytes or more gives
 * HS512 - the same algorithm the real issuer's 94-byte secret selects. A short
 * test secret would exercise HS256 and prove nothing about the live token.
 * fixtureSecretsSelectHs512 refuses mechanically if either fixture drops below
 * that, rather than leaving it to this paragraph.
 */
class ConsoleTokenTest {

    private static final String SECRET = "test-fixture-console-token-secret-padded-past-the-sixty-four-byte-hs512-minimum-not-a-real-secret";
    private static final String OTHER_SECRET = "test-fixture-other-console-token-secret-padded-past-the-sixty-four-byte-hs512-minimum-not-a-real-secret";

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

    /**
     * The class javadoc's premise, made mechanical. Both fixture secrets must
     * be at least 64 UTF-8 bytes, and a token minted with SECRET must carry
     * alg HS512 in its header - otherwise every case above would be testing
     * HS256 or HS384 while the live issuer signs HS512.
     */
    @Test
    void fixtureSecretsSelectHs512() throws Exception {
        assertTrue(SECRET.getBytes(StandardCharsets.UTF_8).length >= 64, "SECRET is under 64 bytes");
        assertTrue(OTHER_SECRET.getBytes(StandardCharsets.UTF_8).length >= 64, "OTHER_SECRET is under 64 bytes");

        String token = token(SECRET, "ADMIN", "admin@example.com", 3600);
        String alg = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
                .build()
                .parseSignedClaims(token)
                .getHeader()
                .getAlgorithm();

        assertEquals("HS512", alg);
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
