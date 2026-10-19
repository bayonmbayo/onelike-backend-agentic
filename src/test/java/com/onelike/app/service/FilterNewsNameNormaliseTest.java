package com.onelike.app.service;

import com.onelike.app.exception.FilterNewsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * No Spring context and no database: FilterNewsConsoleService.normaliseName is
 * static precisely so its three rules - trim, then non-empty and at most
 * NAME_MAX_LENGTH characters, then no ISO control character - can be tested
 * without a context. Both the Console and the agent endpoint call it.
 *
 * Every control character here is built with a (char) cast, never a unicode
 * escape in the source: javac translates those escapes before it parses, so
 * an escaped line break or NUL would be a different program from the one the
 * reader sees. Cases with several inputs use assertAll, so a failure names
 * every input that got through rather than only the first.
 */
class FilterNewsNameNormaliseTest {

    private static final String CODE_NAME_INVALID = "filternews.name.invalid";

    private static String letters(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    private static void assertRejected(String raw, String label) {
        FilterNewsException e = assertThrows(FilterNewsException.class,
                () -> FilterNewsConsoleService.normaliseName(raw), label + " was accepted");
        assertEquals(CODE_NAME_INVALID, e.getMessage(), label);
    }

    @Test
    void exactlyTheMaximumLengthIsAccepted() throws FilterNewsException {
        assertEquals(120, FilterNewsConsoleService.NAME_MAX_LENGTH);
        String name = letters(120);

        assertEquals(name, FilterNewsConsoleService.normaliseName(name));
    }

    @Test
    void oneOverTheMaximumLengthIsRejected() {
        assertRejected(letters(121), "121 characters");
    }

    /**
     * The length rule applies to the trimmed name, not the raw one: padding
     * that takes the raw string past 121 must not cost a 120-character name.
     */
    @Test
    void lengthIsMeasuredAfterTrimming() throws FilterNewsException {
        String name = letters(120);
        String padded = "   " + name + "    ";
        assertEquals(127, padded.length());

        assertEquals(name, FilterNewsConsoleService.normaliseName(padded));
    }

    @Test
    void anInteriorC0ControlIsRejected() {
        assertAll(
                () -> assertRejected("Le" + (char) 0x07 + "Monde", "interior BEL"),
                () -> assertRejected("Le" + (char) 0x09 + "Monde", "interior TAB"),
                () -> assertRejected("Le" + (char) 0x00 + "Monde", "interior NUL"));
    }

    /**
     * trim() strips only characters up to U+0020, so a trailing DEL (0x7F) or
     * NEL (U+0085) survives it and has to be caught by the control rule. These
     * are the two that a "below 0x20" check would miss.
     */
    @Test
    void aTrailingDelOrNelIsRejected() {
        assertAll(
                () -> assertRejected("Le Monde" + (char) 0x7F, "trailing DEL"),
                () -> assertRejected("Le Monde" + (char) 0x85, "trailing U+0085"));
    }

    @Test
    void aNonAsciiLetterIsAccepted() throws FilterNewsException {
        String name = "S" + (char) 0xFC + "ddeutsche";

        assertEquals(name, FilterNewsConsoleService.normaliseName(name));
    }
}
