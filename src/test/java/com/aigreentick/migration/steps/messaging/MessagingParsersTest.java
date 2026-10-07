package com.aigreentick.migration.steps.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagingParsersTest {
    @Test
    void uniformWorkingHoursBecomeOneQuietRange() {
        assertArrayEquals(new int[]{18, 9}, MessagingSettingsStep.quietHours(
                "{\"monday\":{\"enabled\":true,\"start\":\"09:00\",\"end\":\"18:00\"},\"sunday\":{\"enabled\":false}}"));
        assertArrayEquals(new int[]{20, 10}, MessagingSettingsStep.quietHours(
                "[{\"day\":\"mon\",\"isOpen\":true,\"from\":\"10:00\",\"to\":\"20:00\"}]"));
    }

    @Test
    void differentHoursPerDayAreNotRepresentable() {
        assertNull(MessagingSettingsStep.quietHours(
                "[{\"day\":\"mon\",\"from\":\"09:00\",\"to\":\"18:00\"},{\"day\":\"tue\",\"from\":\"10:00\",\"to\":\"17:00\"}]"));
        assertNull(MessagingSettingsStep.quietHours(null));
        assertNull(MessagingSettingsStep.quietHours("garbage"));
    }

    @Test
    void cannedMessageSetsAreFlattened() {
        boolean[] media = {false};
        assertEquals("Hi there\n\nBye", CannedResponsesStep.flatten(com.aigreentick.migration.util.Json.parse(
                "[{\"type\":\"text\",\"text\":\"Hi there\"},{\"type\":\"image\",\"url\":\"x\"},{\"message\":\"Bye\"}]"), media));
        assertTrue(media[0]);
        assertEquals("plain", CannedResponsesStep.flatten(com.aigreentick.migration.util.Json.parse("\"plain\""), new boolean[1]));
    }

    @Test
    void ourNumberMatching() {
        var ours = java.util.List.of(new ChatsPrepareStep.OurPhone(1, 10, 5, "PNID1", "919800000001"),
                new ChatsPrepareStep.OurPhone(1, 13, 5, "PNID5", "919800000005"));
        assertEquals(13, ChatsPrepareStep.match(ours, "PNID5", null).phoneId());
        assertEquals(10, ChatsPrepareStep.match(ours, null, "+91 98000 00001").phoneId());
        assertEquals(10, ChatsPrepareStep.match(ours, null, "9800000001").phoneId());
        assertNull(ChatsPrepareStep.match(ours, null, "911111111111"));
    }

    @Test
    void mediaKeepsOldUrl() {
        assertEquals("https://old.test/a.jpg", MessagesStep.mediaLink(com.aigreentick.migration.util.Json.parse(
                "{\"type\":\"image\",\"image\":{\"link\":\"https://old.test/a.jpg\"}}")));
        assertEquals("https://old.test/b.pdf", MessagesStep.mediaLink(com.aigreentick.migration.util.Json.parse("{\"url\":\"https://old.test/b.pdf\"}")));
        assertNull(MessagesStep.mediaLink(com.aigreentick.migration.util.Json.parse("{\"text\":\"hi\"}")));
    }
}
