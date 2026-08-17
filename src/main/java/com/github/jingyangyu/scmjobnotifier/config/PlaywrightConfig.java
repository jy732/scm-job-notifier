package com.github.jingyangyu.scmjobnotifier.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Proxy;
import jakarta.annotation.PreDestroy;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manages a shared headless Chromium browser instance for scraping SPA career sites (Tesla, Apple,
 * BrassRing) that don't expose a public JSON API.
 *
 * <p>The browser is created once at startup and shared across all Playwright-based scrapers. Each
 * scraper creates its own {@link com.microsoft.playwright.BrowserContext} for isolation. Chromium
 * is auto-installed by Playwright on first run. Launched with {@code
 * --disable-blink-features=AutomationControlled} (hides the {@code navigator.webdriver} flag anti-bot
 * checks look for) and, when {@link ProxyProperties} is configured, routed through the proxy so
 * scrapers work from egress IPs their targets would otherwise block.
 */
@Slf4j
@Configuration
public class PlaywrightConfig {

    private Playwright playwright;
    private Browser browser;

    @Bean
    public Browser playwrightBrowser(ProxyProperties proxyProps) {
        playwright = Playwright.create();
        BrowserType.LaunchOptions options =
                new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(List.of("--disable-blink-features=AutomationControlled"));
        if (proxyProps.isConfigured()) {
            Proxy proxy = new Proxy(proxyProps.server());
            if (proxyProps.hasAuth()) {
                proxy.setUsername(proxyProps.getUsername()).setPassword(proxyProps.getPassword());
            }
            options.setProxy(proxy);
            log.info("Playwright routing through proxy {}", proxyProps.getHost());
        }
        browser = playwright.chromium().launch(options);
        log.info("Playwright headless Chromium browser initialized");
        return browser;
    }

    @PreDestroy
    public void cleanup() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        log.info("Playwright browser closed");
    }
}
