package com.example.audiogateway.dto;

import java.util.Set;

/**
 * Whitelist of allowed audio formats accepted by the Audio Gateway.
 *
 * <p>Future format addition requires ADR + Codex consensus per Faz 24 plan.
 * Codex {@code 019e879c} + Mavis {@code mvs_c922...} agreed on a CLOSED enum to prevent
 * cardinality explosion in {@code stt_audio_bytes_total{format=...}} downstream metric.
 */
public enum AudioFormat {
    WAV("audio/wav"),
    WEBM_OPUS("audio/webm; codecs=opus"),
    PCM16("audio/L16"),
    MP3("audio/mpeg"),
    M4A("audio/mp4"),
    OGG("audio/ogg"),
    FLAC("audio/flac");

    public static final Set<AudioFormat> CLIENT_ALLOWED = Set.of(WAV, WEBM_OPUS, PCM16);
    public static final Set<AudioFormat> ALL = Set.of(values());

    private final String mediaType;

    AudioFormat(final String mediaType) {
        this.mediaType = mediaType;
    }

    public String mediaType() {
        return this.mediaType;
    }

    public static AudioFormat fromMediaType(final String mediaType) {
        for (final AudioFormat f : values()) {
            if (f.mediaType.equalsIgnoreCase(mediaType)) {
                return f;
            }
        }
        return null;
    }
}
