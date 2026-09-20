package com.github.jingyangyu.scmjobnotifier;

import com.github.jingyangyu.scmjobnotifier.config.AdpProperties;
import com.github.jingyangyu.scmjobnotifier.config.AdzunaProperties;
import com.github.jingyangyu.scmjobnotifier.config.BrassRingProperties;
import com.github.jingyangyu.scmjobnotifier.config.DayforceProperties;
import com.github.jingyangyu.scmjobnotifier.config.IcimsProperties;
import com.github.jingyangyu.scmjobnotifier.config.JazzHrProperties;
import com.github.jingyangyu.scmjobnotifier.config.OracleCloudProperties;
import com.github.jingyangyu.scmjobnotifier.config.PaycomProperties;
import com.github.jingyangyu.scmjobnotifier.config.PaylocityProperties;
import com.github.jingyangyu.scmjobnotifier.config.PhenomProperties;
import com.github.jingyangyu.scmjobnotifier.config.ProxyProperties;
import com.github.jingyangyu.scmjobnotifier.config.SuccessFactorsProperties;
import com.github.jingyangyu.scmjobnotifier.config.TeslaProperties;
import com.github.jingyangyu.scmjobnotifier.config.UltiProProperties;
import com.github.jingyangyu.scmjobnotifier.config.WorkdayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the SCM Job Notifier application. Monitors California company career sites for
 * entry-level and internship Supply Chain Management postings, classifies them using Gemini Flash
 * LLM, and sends a single email alert with a Type column.
 */
@SpringBootApplication
@EnableScheduling
@EnableRetry
@EnableConfigurationProperties({
    WorkdayProperties.class,
    IcimsProperties.class,
    OracleCloudProperties.class,
    SuccessFactorsProperties.class,
    PaylocityProperties.class,
    BrassRingProperties.class,
    ProxyProperties.class,
    TeslaProperties.class,
    AdzunaProperties.class,
    UltiProProperties.class,
    JazzHrProperties.class,
    PhenomProperties.class,
    DayforceProperties.class,
    PaycomProperties.class,
    AdpProperties.class
})
public class ScmJobNotifierApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScmJobNotifierApplication.class, args);
    }
}
