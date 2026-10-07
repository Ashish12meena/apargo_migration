package com.aigreentick.migration.steps.plans;

import com.aigreentick.migration.util.Text;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

import java.util.Locale;

/** Old pricing country codes are ISO-2 ("IN") or dial codes ("91", "+91"); the new tables want ISO-2. */
final class Countries {
    private Countries() {}

    /** [iso2, dialCode] or null */
    static String[] resolve(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        PhoneNumberUtil u = PhoneNumberUtil.getInstance();
        if (t.matches("[A-Za-z]{2}")) {
            String iso = t.toUpperCase(Locale.ROOT);
            if (!u.getSupportedRegions().contains(iso)) return null;
            int cc = u.getCountryCodeForRegion(iso);
            return new String[]{iso, cc == 0 ? null : String.valueOf(cc)};
        }
        String d = Text.digits(t);
        if (d.isEmpty() || d.length() > 4) return null;
        String region = u.getRegionCodeForCountryCode(Integer.parseInt(d));
        if (region == null || region.length() != 2 || "ZZ".equals(region)) return null;
        return new String[]{region, d};
    }

    static String name(String iso2) {
        String n = new Locale("", iso2).getDisplayCountry(Locale.ENGLISH);
        return n == null || n.isBlank() ? iso2 : n;
    }
}
