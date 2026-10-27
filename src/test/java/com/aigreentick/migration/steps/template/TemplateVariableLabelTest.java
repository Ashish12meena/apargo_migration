package com.aigreentick.migration.steps.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TemplateVariableLabelTest {
    @Test
    void validAttributeKeyIsKept() {
        assertEquals("first_name", TemplatesStep.variableLabel("first_name", "BODY", -1, 1));
        assertEquals("amount2", TemplatesStep.variableLabel("amount2", "HEADER", -1, 1));
    }

    @Test
    void anythingElseBecomesPositional() {
        assertEquals("body_1", TemplatesStep.variableLabel("{{1}}", "BODY", -1, 1));
        assertEquals("body_2", TemplatesStep.variableLabel("2", "BODY", -1, 2));
        assertEquals("body_3", TemplatesStep.variableLabel("MEMBER ID", "BODY", -1, 3));
        assertEquals("body_1", TemplatesStep.variableLabel("Name", "BODY", -1, 1));
        assertEquals("body_4", TemplatesStep.variableLabel(null, "BODY", -1, 4));
        assertEquals("header_1", TemplatesStep.variableLabel("", "HEADER", -1, 1));
        assertEquals("button_1_1", TemplatesStep.variableLabel("{{1}}", "BUTTON", 1, 1));
    }
}
