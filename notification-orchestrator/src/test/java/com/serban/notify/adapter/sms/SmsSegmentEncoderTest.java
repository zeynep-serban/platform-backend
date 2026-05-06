package com.serban.notify.adapter.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SmsSegmentEncoder unit test (Faz 23.3.1 — Production MVP geniş scope).
 *
 * <p>Covered cases:
 * <ul>
 *   <li>GSM-7 detection: pure ASCII + Latin-1 supplement (within GSM-7 alphabet)</li>
 *   <li>UCS-2 forced by Turkish characters (ç, ğ, ı, ö, ş, ü)</li>
 *   <li>GSM-7 single-segment limit 160 chars</li>
 *   <li>GSM-7 multi-segment 153 char/segment</li>
 *   <li>GSM-7 extension chars (€, [, ]) — count as 2 septets</li>
 *   <li>UCS-2 single-segment limit 70 chars</li>
 *   <li>UCS-2 multi-segment 67 char/segment</li>
 *   <li>Empty / null edge cases</li>
 * </ul>
 */
class SmsSegmentEncoderTest {

    // ─── GSM-7 vs UCS-2 detection ────────────────────────────────────────

    @Test
    void asciiTextIsGsm7() {
        assertThat(SmsSegmentEncoder.isGsm7Encodable("Hello World 123")).isTrue();
        assertThat(SmsSegmentEncoder.encoding("Hello World 123")).isEqualTo("GSM-7");
    }

    @Test
    void turkishCharsForceUcs2() {
        // Turkish-specific chars NOT in GSM-7 alphabet
        assertThat(SmsSegmentEncoder.isGsm7Encodable("Şifre güncellendi")).isFalse();
        assertThat(SmsSegmentEncoder.encoding("Şifre güncellendi")).isEqualTo("UCS-2");
        assertThat(SmsSegmentEncoder.isGsm7Encodable("İçerik onaylandı")).isFalse();
        assertThat(SmsSegmentEncoder.isGsm7Encodable("dağıtım")).isFalse();
    }

    @Test
    void gsm7BasicAlphabetSupportsLatinSupplement() {
        // German umlauts + à è ì ò ù are in GSM-7 basic alphabet
        assertThat(SmsSegmentEncoder.isGsm7Encodable("Grüße Müller")).isTrue();
        assertThat(SmsSegmentEncoder.isGsm7Encodable("àèìòù")).isTrue();
        assertThat(SmsSegmentEncoder.encoding("Grüße Müller")).isEqualTo("GSM-7");
    }

    @Test
    void gsm7ExtensionCharsStillGsm7() {
        // €, [, ], {, } are in GSM-7 extension table
        assertThat(SmsSegmentEncoder.isGsm7Encodable("Price: 5€")).isTrue();
        assertThat(SmsSegmentEncoder.isGsm7Encodable("[INFO]")).isTrue();
        assertThat(SmsSegmentEncoder.encoding("Price: 5€")).isEqualTo("GSM-7");
    }

    @Test
    void emojiForcesUcs2() {
        // Emoji (BMP or surrogate pairs) NOT in GSM-7
        assertThat(SmsSegmentEncoder.isGsm7Encodable("Ok ✓")).isFalse();
        assertThat(SmsSegmentEncoder.encoding("Ok ✓")).isEqualTo("UCS-2");
    }

    // ─── GSM-7 segment counts ────────────────────────────────────────────

    @Test
    void gsm7SingleSegmentExactly160() {
        String text = "A".repeat(160);
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(1);
    }

    @Test
    void gsm7TwoSegments161Chars() {
        // 161 → multi-segment (153 chars/segment), so 2 segments
        String text = "A".repeat(161);
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(2);
    }

    @Test
    void gsm7TwoSegmentsExactly306() {
        // 153 + 153 = 306 → exactly 2 segments
        String text = "A".repeat(306);
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(2);
    }

    @Test
    void gsm7ThreeSegments307Chars() {
        String text = "A".repeat(307);
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(3);
    }

    @Test
    void gsm7ExtensionCharsCountAsTwoSeptets() {
        // 80 € (extension) = 80*2 = 160 septets → still 1 segment
        String text = "€".repeat(80);
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(1);
        // 81 € = 162 septets → 2 segments
        text = "€".repeat(81);
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(2);
    }

    // ─── UCS-2 segment counts ────────────────────────────────────────────

    @Test
    void ucs2SingleSegmentExactly70Chars() {
        // Use Turkish chars to force UCS-2
        String text = "ş".repeat(70);
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(1);
    }

    @Test
    void ucs2TwoSegments71Chars() {
        // 71 → multi-segment (67 chars/segment), so 2 segments
        String text = "ş".repeat(71);
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(2);
    }

    @Test
    void ucs2TwoSegmentsExactly134() {
        // 67 + 67 = 134 → exactly 2 segments
        String text = "ğ".repeat(134);
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(2);
    }

    @Test
    void ucs2ThreeSegments135Chars() {
        String text = "ı".repeat(135);
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(3);
    }

    // ─── Edge cases ──────────────────────────────────────────────────────

    @Test
    void emptyOrNullSegmentZero() {
        assertThat(SmsSegmentEncoder.segmentCount(null)).isEqualTo(0);
        assertThat(SmsSegmentEncoder.segmentCount("")).isEqualTo(0);
    }

    @Test
    void nullIsGsm7Encodable() {
        assertThat(SmsSegmentEncoder.isGsm7Encodable(null)).isTrue();
    }

    @Test
    void singleCharSegmentOne() {
        assertThat(SmsSegmentEncoder.segmentCount("A")).isEqualTo(1);
        assertThat(SmsSegmentEncoder.segmentCount("ş")).isEqualTo(1);
    }

    @Test
    void shortTurkishMessage1Segment() {
        // "Merhaba dünya" → 13 chars; UCS-2 limit 70 → 1 segment
        String text = "Merhaba dünya, sipariş onaylandı.";
        assertThat(SmsSegmentEncoder.encoding(text)).isEqualTo("UCS-2");
        assertThat(SmsSegmentEncoder.segmentCount(text)).isEqualTo(1);
    }
}
