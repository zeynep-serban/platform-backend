package com.example.endpointadmin.model;

/**
 * Desktop wallpaper fit styles for the #508 Endpoint Display Policy.
 *
 * <p>The {@code registryValue} is the canonical {@code WallpaperStyle} value the
 * agent writes under {@code HKLM\...\CurrentVersion\Policies\System} (GPO-locked,
 * machine-wide). It is carried here so the agent track has a single source of
 * truth and the backend validator can fail closed on any out-of-domain value.
 */
public enum WallpaperStyle {
    CENTER("0"),
    STRETCH("2"),
    FIT("6"),
    FILL("10"),
    SPAN("22");

    private final String registryValue;

    WallpaperStyle(String registryValue) {
        this.registryValue = registryValue;
    }

    public String registryValue() {
        return registryValue;
    }
}
