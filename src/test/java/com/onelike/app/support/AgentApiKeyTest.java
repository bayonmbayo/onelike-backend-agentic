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
 * test-fixture-...-not-a-real-secret convention. The hyphens mean such a
 * literal can never match the redactor pattern (a quoted run of 28 to 48
 * letters and digits), which NoKeyShapedTestLiteralTest refuses in
 * src/test/java. Older fixtures are not all converted: the four jwt.secret
 * and onelike.news.filter.apikey property strings in
 * UserNewsContainerFrontInsertTest and UserNewsContainerQuotaTest predate the
 * convention and do not follow it (backlog row (a), 2026-10-17).
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
