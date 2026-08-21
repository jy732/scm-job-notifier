package com.github.jingyangyu.scmjobnotifier.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tesla careers scraping via Bright Data's Web Unlocker.
 *
 * <p>Tesla's careers board ({@code /cua-api/apps/careers/state}) sits behind Akamai Bot Manager
 * plus an app-level {@code cpr_chlge} challenge that blocks every direct and Playwright approach we
 * tried (headless, headful real Chrome, residential IP — all "Access Denied"; see {@code
 * docs/adzuna-migration-audit.md}). The Web Unlocker mints Akamai-valid cookies and returns the
 * JSON board. Disabled unless {@code job.tesla.enabled=true} and a token is set, so it's a no-op
 * otherwise. The token is a secret — supply it via {@code .env}, never commit it.
 */
@ConfigurationProperties(prefix = "job.tesla")
@Getter
@Setter
public class TeslaProperties {

    private boolean enabled = false;
    private String brightdataToken = "";
    private String brightdataZone = "scm_unlocker";

    /** True only when enabled and a Web Unlocker token is configured. */
    public boolean isConfigured() {
        return enabled && brightdataToken != null && !brightdataToken.isBlank();
    }
}
