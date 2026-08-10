package com.github.jingyangyu.scmjobnotifier.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manages a shared headless Chromium browser instance for scraping SPA career sites (Tesla, Apple)
 * that don't expose a public JSON API.
 *
 * <p>The browser is created once at startup and shared across all Playwright-based scrapers. Each
 * scraper creates its own {@link com.microsoft.playwright.BrowserContext} for isolation. Chromium
 * is auto-installed by Playwright on first run.
 */
@Slf4j
@Configuration
public class PlaywrightConfig {

    private Playwright playwright;
    private Browser browser;

    @Bean
    public Browser playwrightBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
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
