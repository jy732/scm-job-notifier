package com.github.jingyangyu.scmjobnotifier.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Exercises the flat @ConfigurationProperties: getters/setters + isConfigured branches. */
class FlatPropertiesTest {

    @Test
    void adzunaConfigured() {
        AdzunaProperties p = new AdzunaProperties();
        assertThat(p.isConfigured()).isFalse(); // no keys yet
        p.setAppId("id");
        p.setAppKey("key");
        p.setEnabled(true);
        p.setThrottleMinutes(30);
        p.setMaxDaysOld(15);
        p.setPages(2);
        p.setCountry("us");
        assertThat(p.isConfigured()).isTrue();
        assertThat(p.getAppId()).isEqualTo("id");
        assertThat(p.getAppKey()).isEqualTo("key");
        assertThat(p.getThrottleMinutes()).isEqualTo(30);
        assertThat(p.getMaxDaysOld()).isEqualTo(15);
        assertThat(p.getPages()).isEqualTo(2);
        assertThat(p.getCountry()).isEqualTo("us");
        assertThat(p.isEnabled()).isTrue();
    }

    @Test
    void adzunaDisabledNotConfigured() {
        AdzunaProperties p = new AdzunaProperties();
        p.setAppId("id");
        p.setAppKey("key");
        p.setEnabled(false);
        assertThat(p.isConfigured()).isFalse();
    }

    @Test
    void tesla() {
        TeslaProperties p = new TeslaProperties();
        assertThat(p.isConfigured()).isFalse(); // disabled by default
        p.setEnabled(true);
        p.setBrightdataToken("tok");
        p.setBrightdataZone("z");
        assertThat(p.isConfigured()).isTrue();
        assertThat(p.getBrightdataToken()).isEqualTo("tok");
        assertThat(p.getBrightdataZone()).isEqualTo("z");
        assertThat(p.isEnabled()).isTrue();
    }

    @Test
    void proxy() {
        ProxyProperties p = new ProxyProperties();
        assertThat(p.isConfigured()).isFalse();
        assertThat(p.hasAuth()).isFalse();
        p.setEnabled(true);
        p.setHost("proxy.example.com");
        p.setPort(33335);
        assertThat(p.isConfigured()).isTrue();
        assertThat(p.server()).contains("proxy.example.com").contains("33335");
        p.setUsername("u");
        p.setPassword("pw");
        assertThat(p.hasAuth()).isTrue();
        assertThat(p.getUsername()).isEqualTo("u");
        assertThat(p.getPassword()).isEqualTo("pw");
        assertThat(p.getHost()).isEqualTo("proxy.example.com");
        assertThat(p.getPort()).isEqualTo(33335);
    }
}
