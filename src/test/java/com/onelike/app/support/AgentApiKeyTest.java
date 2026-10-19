package com.onelike.app.support;

import com.onelike.app.exception.FilterNewsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * No Spring context and no database: AgentApiKey takes its key through the
 * constructor precisely so the comparison rules can be tested against a key of
 * this class's own choosing. Nothing here touches onelike_test, and nothing
 * here uses the real configured key.
 *
 * The key used below is a deliberately fake fixture. Its shape does not
 * matter: AgentApiKey checks no format, only a trimmed, non-blank, exact
 * match, so what is being tested is the accept/reject decision, not a
 * particular secret. The cases below need only that KEY is non-blank and has
 * no leading or trailing whitespace of its own.
 *
 * New credential-shaped fixtures in src/test follow the
 * test-fixture-...-not-a-real-secret convention. Such a literal DOES match
 * NoKeyShapedTestLiteralTest's raw pattern - its class now includes - and _ -
 * and passes that scan only by the convention exemption, which requires every
 * segment to be 1 to 16 lowercase letters or digits. The property-string
 * fixtures for jwt.secret and onelike.news.filter.apikey now live in one
 * place, IsolatedH2Test.java:86-87, rather than in the individual H2 test
 * classes.
 */
class AgentApiKeyTest {

    private static final String KEY = "test-fixture-agent-key-not-a-real-secret";

    private static final String CODE_INVALID = "filternews.apikey.invalid";

    private final AgentApiKey agentApiKey = new AgentApiKey(KEY);

    @Test
    void acceptsTheConfiguredKey() {
        assertDoesNotThrow(() -> agentApiKey.requireValidKey(KEY));
    }

    /**
     * Both sides are trimmed, and this is the case that makes that mechanical
     * rather than a caveat in a comment. The configured value arrives from a
     * .env file; a stray CR or trailing space on that line would sit INSIDE the
     * value, and the symptom would be "the agent's correct key is rejected" -
     * a remote failure with no visible local cause.
     */
    @Test
    void acceptsTheConfiguredKeyWithSurroundingWhitespace() {
        assertDoesNotThrow(() -> agentApiKey.requireValidKey("  " + KEY + " \r\n"));
    }

    @Test
    void rejectsANullKey() {
        FilterNewsException e = assertThrows(FilterNewsException.class, () -> agentApiKey.requireValidKey(null));

        assertEquals(CODE_INVALID, e.getMessage());
    }

    /**
     * Blank, not merely empty: a key of spaces trims to nothing and must not
     * slip past the empty check.
     */
    @Test
    void rejectsABlankKey() {
        FilterNewsException e = assertThrows(FilterNewsException.class, () -> agentApiKey.requireValidKey("   "));

        assertEquals(CODE_INVALID, e.getMessage());
    }

    @Test
    void rejectsAWrongKey() {
        FilterNewsException e = assertThrows(FilterNewsException.class,
                () -> agentApiKey.requireValidKey("test-fixture-wrong-key-not-a-real-secret"));

        assertEquals(CODE_INVALID, e.getMessage());
    }

    /**
     * A key that shares a long prefix with the configured one still has to be
     * rejected with the same code: MessageDigest.isEqual makes a near miss
     * indistinguishable from a wild miss in timing, and the response makes it
     * indistinguishable in content.
     */
    @Test
    void rejectsAKeyDifferingOnlyInItsLastCharacter() {
        String almost = KEY.substring(0, KEY.length() - 1) + (KEY.endsWith("A") ? "B" : "A");

        FilterNewsException e = assertThrows(FilterNewsException.class, () -> agentApiKey.requireValidKey(almost));

        assertEquals(CODE_INVALID, e.getMessage());
    }

    /**
     * Fails closed. A blank configured key is what production injects when
     * ONELIKE_NEWS_FILTER_APIKEY is present but empty (AgentApiKeyPropertyTest);
     * an absent variable refuses to start the app instead. A blank presented
     * key is rejected by the presented-key check whatever the configured key
     * is, so the blank half of the configured-key check is defence in depth:
     * dropping it alone leaves every case here passing.
     */
    @Test
    void rejectsEverythingWhenTheConfiguredKeyIsBlank() {
        AgentApiKey unconfigured = new AgentApiKey("   ");

        assertEquals(CODE_INVALID,
                assertThrows(FilterNewsException.class, () -> unconfigured.requireValidKey("   ")).getMessage());
        assertEquals(CODE_INVALID,
                assertThrows(FilterNewsException.class, () -> unconfigured.requireValidKey("")).getMessage());
        assertEquals(CODE_INVALID,
                assertThrows(FilterNewsException.class, () -> unconfigured.requireValidKey(KEY)).getMessage());
    }

    @Test
    void rejectsEverythingWhenTheConfiguredKeyIsNull() {
        AgentApiKey unconfigured = new AgentApiKey(null);

        assertEquals(CODE_INVALID,
                assertThrows(FilterNewsException.class, () -> unconfigured.requireValidKey(KEY)).getMessage());
        assertEquals(CODE_INVALID,
                assertThrows(FilterNewsException.class, () -> unconfigured.requireValidKey(null)).getMessage());
    }
}
