package com.serban.notify.adapter.sms;

/**
 * SMS segment encoder — GSM-7 vs UCS-2 detection + segment count
 * (Faz 23.3.1 — Production MVP geniş scope).
 *
 * <p>SMS encoding:
 * <ul>
 *   <li>GSM-7: 7-bit alphabet, 160 chars/segment (concatenated 153 chars/segment)</li>
 *   <li>UCS-2: 16-bit Unicode, 70 chars/segment (concatenated 67 chars/segment)</li>
 * </ul>
 *
 * <p>Turkish text typically requires UCS-2 due to chars like ç, ğ, ı, İ, ö, ş, ü
 * which are NOT in GSM-7 default + extension table.
 *
 * <p>NetGSM API parameters:
 * <ul>
 *   <li>{@code encoding}: TR (UCS-2 implied) or normal (GSM-7 7-bit)</li>
 *   <li>{@code msgheader}: sender ID (configured)</li>
 *   <li>{@code segments}: 1, 2, 3 — billing implication</li>
 * </ul>
 */
public final class SmsSegmentEncoder {

    /** GSM-7 default alphabet (basic Latin + common European). */
    private static final String GSM7_BASIC =
        "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡"
        + "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    /** GSM-7 extension table (chars that take 2 septets via ESC). */
    private static final String GSM7_EXTENSION = "\f^{}\\[~]|€";

    private SmsSegmentEncoder() {}

    /** True if all chars in {@code text} are encodable in GSM-7 (basic+extension). */
    public static boolean isGsm7Encodable(String text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (GSM7_BASIC.indexOf(c) < 0 && GSM7_EXTENSION.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compute SMS segment count.
     *
     * <p>Single-segment limits:
     * <ul>
     *   <li>GSM-7: 160 chars (extension chars count as 2 septets)</li>
     *   <li>UCS-2: 70 code units (surrogate pair counts as 2)</li>
     * </ul>
     *
     * <p>Multi-segment limits (UDH overhead):
     * <ul>
     *   <li>GSM-7 concatenated: 153 chars/segment</li>
     *   <li>UCS-2 concatenated: 67 code units/segment</li>
     * </ul>
     */
    public static int segmentCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        boolean gsm7 = isGsm7Encodable(text);
        int length = gsm7 ? gsm7SeptetLength(text) : text.length();
        int singleLimit = gsm7 ? 160 : 70;
        int multiLimit = gsm7 ? 153 : 67;
        if (length <= singleLimit) return 1;
        return (int) Math.ceil((double) length / multiLimit);
    }

    /** GSM-7 septet length (extension chars count as 2). */
    private static int gsm7SeptetLength(String text) {
        int len = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (GSM7_EXTENSION.indexOf(c) >= 0) {
                len += 2;  // ESC + extension char
            } else {
                len += 1;
            }
        }
        return len;
    }

    /**
     * Encoding hint for provider API.
     *
     * @return "GSM-7" or "UCS-2"
     */
    public static String encoding(String text) {
        return isGsm7Encodable(text) ? "GSM-7" : "UCS-2";
    }
}
