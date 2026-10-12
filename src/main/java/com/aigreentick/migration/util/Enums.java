package com.aigreentick.migration.util;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Old free-text values -> target ENUM values (exactly as declared in the server DDL). */
public final class Enums {
    private Enums() {}

    /** value upper-cased if it is one of the allowed values, else null */
    public static String pick(String raw, Set<String> allowed) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return allowed.contains(v) ? v : null;
    }

    /** map lookup with lower-cased key */
    public static String map(Map<String, String> m, String raw) {
        if (raw == null) return null;
        return m.get(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static final Set<String> TEMPLATE_CATEGORY = Set.of("AUTHENTICATION", "MARKETING", "UTILITY");
    public static final Set<String> TEMPLATE_STATUS = Set.of("APPROVED", "DISABLED", "DRAFT", "FAILED", "NEW_CREATED",
            "PAUSED", "PENDING", "REJECTED", "SUBMITTED", "UNKNOWN");
    public static final Set<String> COMPONENT_TYPE = Set.of("BODY", "BUTTONS", "CAROUSEL", "FOOTER", "HEADER", "LIMITED_TIME_OFFER");
    public static final Set<String> COMPONENT_FORMAT = Set.of("DOCUMENT", "IMAGE", "LOCATION", "PRODUCT", "TEXT", "VIDEO");
    public static final Set<String> BUTTON_TYPE = Set.of("CATALOG", "COPY_CODE", "MPM", "OTP", "PHONE_NUMBER", "QUICK_REPLY", "SPM", "URL");
    public static final Set<String> CAROUSEL_BUTTON_TYPE = Set.of("PHONE_NUMBER", "QUICK_REPLY", "URL");
    public static final Set<String> CARD_FORMAT = Set.of("DOCUMENT", "IMAGE", "VIDEO");
    public static final Set<String> MESSAGE_TYPE = Set.of("AUDIO", "BUTTON", "CONTACTS", "DOCUMENT", "IMAGE", "INTERACTIVE",
            "LOCATION", "ORDER", "REACTION", "STICKER", "SYSTEM", "TEMPLATE", "TEXT", "UNSUPPORTED", "VIDEO");
    public static final Set<String> MESSAGE_STATUS = Set.of("DELETED", "DELIVERED", "EXPIRED", "FAILED", "PROCESSING",
            "QUEUED", "READ", "REJECTED", "SENT");
}
