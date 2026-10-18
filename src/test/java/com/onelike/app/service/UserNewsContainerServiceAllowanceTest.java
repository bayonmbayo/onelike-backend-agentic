package com.onelike.app.service;

import com.onelike.app.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * themeAllowance on its own: no Spring context, no database, no HTTP. The two
 * numbers it reads are @Value fields, so they are set here directly - the point
 * is the arithmetic of the clamp, not how Spring resolves a property, and this
 * class must stay runnable with no onelike_test, no .env and no MySQL.
 *
 * Why the clamp is worth seven cases of its own: the stored value arrives from an
 * admin's hand-typed UPDATE, so it is the one number in this feature that no
 * code path validates on the way in. A typed 25 must not grant 25 themes, and
 * a typed -3 must not make the >= comparison in createContainer pass for every
 * create. Both are read-time only, so a test of the endpoint alone would not
 * tell you which of the two numbers was wrong.
 *
 * Ceiling and default are this class's own choosing (10 and 1), matching the
 * configured values so the cases read as the real ones - but nothing here
 * depends on application.properties.
 */
class UserNewsContainerServiceAllowanceTest {

    private static final int CEILING = 10;
    private static final int DEFAULT = 1;

    private UserNewsContainerService service;

    @BeforeEach
    void setUp() {
        service = new UserNewsContainerService();
        ReflectionTestUtils.setField(service, "maxThemesPerUser", CEILING);
        ReflectionTestUtils.setField(service, "defaultThemesPerUser", DEFAULT);
    }

    private User userWithQuota(Integer quota) {
        User user = new User();
        user.setThemequota(quota);
        return user;
    }

    /**
     * The case every existing row in the shared `user` table is in, including
     * every row the communo backend inserts - it does not map this column at
     * all. NULL means "the configured default", never zero.
     */
    @Test
    void aNullQuotaReadsTheConfiguredDefault() {
        assertEquals(DEFAULT, service.themeAllowance(userWithQuota(null)));
    }

    /** The default written out explicitly must not differ from NULL. */
    @Test
    void aQuotaOfOneReadsOne() {
        assertEquals(1, service.themeAllowance(userWithQuota(1)));
    }

    /** The whole point of the column: an admin raising one person's allowance. */
    @Test
    void aQuotaBelowTheCeilingIsHonouredAsStored() {
        assertEquals(8, service.themeAllowance(userWithQuota(8)));
    }

    /**
     * The ceiling is hard: no per-user value may exceed it, however it got into
     * the column.
     */
    @Test
    void aQuotaAboveTheCeilingIsClampedToTheCeiling() {
        assertEquals(CEILING, service.themeAllowance(userWithQuota(25)));
    }

    /**
     * Zero is a legitimate stored value and is NOT the same as NULL: it means
     * "no custom cards at all", so it must not fall through to the default.
     */
    @Test
    void aQuotaOfZeroReadsZeroAndNotTheDefault() {
        assertEquals(0, service.themeAllowance(userWithQuota(0)));
    }

    /**
     * A negative clamps to zero rather than staying negative. Left negative it
     * would still deny every create (count >= negative is always true), so this
     * case is about the number the response REPORTS: a client told
     * "allowed: -3" has no sensible copy for it.
     */
    @Test
    void aNegativeQuotaIsClampedToZero() {
        assertEquals(0, service.themeAllowance(userWithQuota(-3)));
    }

    /**
     * A caller with no User row at all - what a missing, blank or unknown uuid
     * resolves to on the read path - takes the same branch as a NULL quota.
     * Asserted here rather than left to the endpoint test because this is the
     * one input that would NPE instead of returning a wrong number.
     */
    @Test
    void aCallerWithNoUserRowReadsTheConfiguredDefault() {
        assertEquals(DEFAULT, service.themeAllowance(null));
    }
}
