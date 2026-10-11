package com.onelike.app.support;

import com.onelike.app.exception.FilterNewsException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Console's only identity source: the Authorization header of the request.
 *
 * This is NOT a Spring Security filter chain and must not become one.
 * spring-security is commented out of pom.xml, so SecurityFilterChain is not
 * even on the compile classpath; un-commenting the starter would activate
 * Boot's default @ConditionalOnDefaultWebSecurity chain and lock down all 19
 * existing controllers, none of which authenticate anything today. A plain
 * component called as the first statement of each Console method gates exactly
 * the four endpoints that need it and nothing else.
 *
 * The role must not be client-assertable, which is why the Console is the one
 * feature here that does NOT take uuid/email/firstname/name/displayname in the
 * request body the way Pin and Container do: role and caller email come only
 * from the signed token.
 *
 * Nothing here is ever logged - not the header, not the token, not the secret
 * and not a claim. The controller logs the filter name and id only, both of
 * which come from the request body.
 *
 * The token is minted by a different host (communo.onelike.app) than the one
 * verifying it here (api.onelike.app), so the two deployments must carry a
 * byte-identical JWT_SECRET. A mismatch answers 401 on every call with a
 * signature that is perfectly valid at the issuer.
 */
@Component
public class ConsoleToken {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_CLAIM = "role";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_MODERATOR = "MODERATOR";

    private final String secret;

    /**
     * Constructor-injected rather than a @Value field so the verification rules
     * can be tested with a secret of the test's own choosing and no Spring
     * context at all. jwt.secret=${JWT_SECRET} already exists in both
     * properties files; this class adds no new configuration key.
     */
    public ConsoleToken(@Value("${jwt.secret}") String secret) {
        this.secret = secret;
    }

    /**
     * Verifies the header and returns the caller's email (the token's sub),
     * which is the only thing that may ever go into FilterNews.createdby.
     *
     * ADMIN and MODERATOR are treated identically on all four endpoints,
     * including the hard delete.
     *
     * Beware the staleness this inherits: the role claim is baked in at login
     * and jwt.expiration is 365 days, so a user promoted to ADMIN after their
     * current token was issued stays USER here until they sign out and back in.
     * That is why 403 and 401 share one remedy in the client.
     */
    public String requireAdminOrModerator(String authorization) throws FilterNewsException {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new FilterNewsException("filternews.unauthorized");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new FilterNewsException("filternews.unauthorized");
        }

        Claims claims;
        try {
            // Verbatim from the issuer repo's security/JWT.java, minus its
            // Spring Security imports. This one call covers the signature AND
            // the expiry: jjwt throws ExpiredJwtException (a JwtException) for
            // a token past exp, so there is no separate expiry check to forget.
            claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes())).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new FilterNewsException("filternews.unauthorized");
        }

        Object role = claims.get(ROLE_CLAIM);
        String roleName = role == null ? null : role.toString();
        if (!ROLE_ADMIN.equals(roleName) && !ROLE_MODERATOR.equals(roleName)) {
            throw new FilterNewsException("filternews.forbidden");
        }

        String subject = claims.getSubject();
        if (subject == null || subject.trim().isEmpty()) {
            throw new FilterNewsException("filternews.unauthorized");
        }

        return subject;
    }
}
