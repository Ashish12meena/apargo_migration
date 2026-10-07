package com.aigreentick.migration.util;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * Old mobiles come with or without country code, '+', '00', spaces, leading zeros. The new contact service keys
 * contacts by E.164 ({@code contacts.normalized_phone}); everything that matches a phone (contacts, blacklist,
 * reports, chats) goes through this one class so the same number always normalizes the same way.
 */
public final class PhoneNormalizer {
    private static final PhoneNumberUtil U = PhoneNumberUtil.getInstance();

    /** e164Digits without '+', e.g. 919876543210 */
    public record Phone(String e164Digits, String dialCode, String national, String region, boolean valid) {
        public String normalized(boolean withPlus) { return withPlus ? "+" + e164Digits : e164Digits; }
    }

    private final String defaultDial;

    public PhoneNormalizer(String defaultDialCode) {
        String d = Text.digits(defaultDialCode);
        this.defaultDial = d.isEmpty() ? "91" : d;
    }

    /** @return null when the value has fewer than 5 digits (not a phone number) */
    public Phone normalize(String raw, String dialHint) {
        if (raw == null) return null;
        String t = raw.trim();
        boolean plus = t.startsWith("+");
        String d = Text.digits(t);
        if (d.startsWith("00")) { d = d.substring(2); plus = true; }
        if (d.length() < 5 || d.length() > 17) return null;
        String hint = Text.digits(dialHint);
        if (hint.isEmpty() || hint.length() > 4) hint = defaultDial;

        if (plus) {
            Phone p = intl(d);
            if (p != null) return p;
        } else {
            if (d.startsWith(hint) && d.length() >= hint.length() + 7) {
                Phone p = intl(d);
                if (p != null && p.valid()) return p;
            }
            Phone n = national(d, hint);
            if (n != null && n.valid()) return n;
            if (d.length() >= 11 && !d.startsWith("0")) {
                Phone p = intl(d);
                if (p != null && p.valid()) return p;
            }
            if (n != null) return n;
        }
        // last resort, not a valid number: keep digits with the hinted dial code
        String national = d.replaceFirst("^0+", "");
        if (national.length() < 5) return null;
        if (d.startsWith(hint) && d.length() >= hint.length() + 7) national = d.substring(hint.length());
        String region = U.getRegionCodeForCountryCode(Integer.parseInt(hint));
        return new Phone(hint + national, hint, national, region.length() == 2 ? region : null, false);
    }

    private static Phone intl(String digits) {
        try {
            return of(U.parse("+" + digits, "ZZ"));
        } catch (NumberParseException e) {
            return null;
        }
    }

    private static Phone national(String digits, String dial) {
        try {
            String region = U.getRegionCodeForCountryCode(Integer.parseInt(dial));
            if ("ZZ".equals(region)) return null;
            return of(U.parse(digits, region));
        } catch (NumberParseException | NumberFormatException e) {
            return null;
        }
    }

    private static Phone of(PhoneNumber pn) {
        String cc = String.valueOf(pn.getCountryCode());
        String nsn = U.getNationalSignificantNumber(pn);
        if (nsn.isEmpty()) return null;
        String region = U.getRegionCodeForNumber(pn);
        if (region == null || region.length() != 2) region = U.getRegionCodeForCountryCode(pn.getCountryCode());
        return new Phone(cc + nsn, cc, nsn, region != null && region.length() == 2 ? region : null, U.isValidNumber(pn));
    }
}
