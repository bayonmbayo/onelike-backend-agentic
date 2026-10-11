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
 * The key used below is a 43-character base64url string, the shape the real
 * generator produces (32 random bytes, no +, / or =, so it needs no escaping
 * in a query string). Its value is irrelevant to every assertion - what is
 * being tested is the accept/reject decision, not a particular secret.
 */
class AgentApiKeyTest {

    private static final String KEY = "Zm9vYmFyLXRlc3Qta2V5LW5vdC1hLXJlYWwtc2VjcmV0";

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
                () -> agentApiKey.requireValidKey("bm90LXRoZS1yaWdodC1rZXktYXQtYWxsLW5vdC1jbG9zZQ"));

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
     * Fails closed. This is the case that would otherwise authenticate every
     * caller: with a blank configured key, an equally blank presented key
     * compares equal. The property has no inline default so the app should
     * refuse to start first, but the guard must not depend on that.
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
