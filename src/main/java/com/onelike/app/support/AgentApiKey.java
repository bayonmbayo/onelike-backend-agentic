package com.onelike.app.support;

import com.onelike.app.exception.FilterNewsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The agent write path's only identity source: one shared API key, read from
 * the environment.
 *
 * This lives beside ConsoleToken in support/ rather than in a new security/
 * package, and for the same reason ConsoleToken does: security/ is where a
 * later contributor drops a SecurityFilterChain, and that type is not even on
 * the compile classpath here - spring-security is commented out of pom.xml, so
 * un-commenting it to get the type would activate Boot's default
 * @ConditionalOnDefaultWebSecurity chain and lock down all 19 controllers,
 * none of which authenticate anything today.
 *
 * This is NOT the Console's gate and carries none of its guarantees. The key is
 * shared, it is not per-user, and it asserts no role: it authenticates a
 * machine, not a person. That is why the endpoint it guards is add-only, and
 * why FilterNews.createdby is the literal "agent" there rather than an email.
 *
 * Nothing in this class is ever logged - not the configured key, not the
 * presented one, not a fragment of either and not their lengths. A length is
 * enough to tell a prober that they have the right kind of string.
 */
@Component
public class AgentApiKey {

    private static final String CODE_INVALID = "filternews.apikey.invalid";

    private final String apikey;

    /**
     * Constructor-injected rather than a @Value field so the comparison rules
     * can be tested against a key of the test's own choosing with no Spring
     * context at all.
     *
     * The property is onelike.news.filter.apikey=${ONELIKE_NEWS_FILTER_APIKEY}
     * with no inline default, so a missing variable refuses to start the app
     * instead of booting with an empty key - the same choice as
     * onelike.hibp.api-key and onelike.webshare.api-key.
     */
    public AgentApiKey(@Value("${onelike.news.filter.apikey}") String apikey) {
        this.apikey = apikey;
    }

    /**
     * Accepts the presented key or throws filternews.apikey.invalid, which the
     * controller answers as 401.
     *
     * Missing, blank, wrong, and "the server's own key is unset" all answer
     * that one code and that one status, deliberately. Distinguishing them
     * would tell a prober whether they have the parameter name right, which is
     * the one piece of information they cannot get from the endpoint otherwise.
     *
     * Two details are load-bearing:
     *
     * - MessageDigest.isEqual on the UTF-8 bytes, not String.equals: isEqual is
     *   documented as time-constant for equal-length inputs, so a near-miss
     *   does not leak a prefix length through timing.
     *
     * - trim() on BOTH sides. The configured value arrives from a .env file via
     *   spring-dotenv, and a stray CR at the end of that line would sit inside
     *   the value and present as "the agent's correct key is rejected" - a
     *   remote symptom with no local cause. The env writer refuses to create
     *   that byte; this is the second, mechanical line of defence rather than
     *   another comment warning about it.
     */
    public void requireValidKey(String presented) throws FilterNewsException {
        // Fails closed. The null half is what stops a NullPointerException at
        // apikey.trim() below when the configured key is null and a non-blank
        // key is presented: without it that caller gets a 500, still not an
        // authentication. A null key comes only from direct construction - in
        // production Spring refuses to start when ONELIKE_NEWS_FILTER_APIKEY is
        // absent, and injects "" when it is empty. The blank half is redundant
        // with the presented-key check below plus MessageDigest.isEqual, and is
        // kept as defence in depth.
        if (apikey == null || apikey.trim().isEmpty()) {
            throw new FilterNewsException(CODE_INVALID);
        }
        if (presented == null || presented.trim().isEmpty()) {
            throw new FilterNewsException(CODE_INVALID);
        }

        byte[] expected = apikey.trim().getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.trim().getBytes(StandardCharsets.UTF_8);

        if (!MessageDigest.isEqual(expected, actual)) {
            throw new FilterNewsException(CODE_INVALID);
        }
    }
}
