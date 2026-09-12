package com.github.jingyangyu.scmjobnotifier.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional outbound proxy for scrapers whose targets block this host's egress IP.
 *
 * <p>Some ATSes (Meta Careers, Kenexa BrassRing) aggressively block datacenter/repeat IPs with a
 * 400/429 error page — see the notes in {@code docs/adzuna-migration-audit.md}. Pointing this at a
 * residential/rotating HTTP proxy routes the WebClient- and Playwright-based scrapers around the
 * block. Disabled (direct connection) unless {@code job.proxy.enabled=true} and a host is set, so
 * it is a no-op in environments that aren't blocked.
 */
@ConfigurationProperties(prefix = "job.proxy")
@Getter
@Setter
public class ProxyProperties {

    private boolean enabled = false;
    private String host;
    private int port;
    private String username;
    private String password;

    /** True only when the proxy is enabled and a usable host/port is configured. */
    public boolean isConfigured() {
        return enabled && host != null && !host.isBlank() && port > 0;
    }

    /** True when the proxy requires authentication. */
    public boolean hasAuth() {
        return username != null && !username.isBlank();
    }

    /** Proxy server URL in Playwright/curl form ({@code http://host:port}). */
    public String server() {
        return "http://" + host + ":" + port;
    }
}
