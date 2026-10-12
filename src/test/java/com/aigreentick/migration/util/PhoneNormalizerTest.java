package com.aigreentick.migration.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhoneNormalizerTest {
    private final PhoneNormalizer p = new PhoneNormalizer("91");

    @Test
    void sameNumberInEveryOldFormatGivesOneE164() {
        String[] variants = {"9876543210", "919876543210", "+91 98765 43210", "09876543210", "0091-98765-43210", "98765-43210"};
        for (String v : variants) {
            PhoneNormalizer.Phone r = p.normalize(v, "91");
            assertNotNull(r, v);
            assertEquals("+919876543210", r.normalized(true), v);
            assertEquals("91", r.dialCode(), v);
            assertEquals("9876543210", r.national(), v);
            assertEquals("IN", r.region(), v);
            assertTrue(r.valid(), v);
        }
    }

    @Test
    void otherCountries() {
        PhoneNormalizer.Phone us = p.normalize("+1 415 555 2671", "1");
        assertEquals("+14155552671", us.normalized(true));
        assertEquals("US", us.region());
        assertEquals("14155552671", p.normalize("4155552671", "1").e164Digits());
    }

    @Test
    void garbage() {
        assertNull(p.normalize("12", "91"));
        assertNull(p.normalize(null, "91"));
        PhoneNormalizer.Phone odd = p.normalize("1234567", "91");
        assertNotNull(odd);
        assertFalse(odd.valid());
    }
}
