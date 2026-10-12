package com.aigreentick.migration.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextAndJsonTest {
    @Test
    void slugLikePhase1() {
        assertEquals("c1-project-10", Text.slugify("c1-project-10"));
        assertEquals("night-shift", Text.slugify("  Night Shift! "));
        assertEquals("cafe-team", Text.slugify("Café Team"));
        assertEquals("company_name", Text.snake("Company Name"));
    }

    @Test
    void cutKeepsSurrogatePairs() {
        assertEquals("ab", Text.cut("ab😀", 3));
        assertNull(Text.cut(null, 3));
    }

    @Test
    void jsonRepairsDoubleEncoding() {
        assertEquals("{\"a\":1}", Json.normalize("\"{\\\"a\\\":1}\""));
        assertNull(Json.normalize("not json {"));
    }
}
