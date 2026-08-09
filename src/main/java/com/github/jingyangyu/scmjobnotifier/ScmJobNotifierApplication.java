package com.github.jingyangyu.scmjobnotifier;

import com.github.jingyangyu.scmjobnotifier.config.IcimsProperties;
import com.github.jingyangyu.scmjobnotifier.config.OracleCloudProperties;
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
    OracleCloudProperties.class
})
public class ScmJobNotifierApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScmJobNotifierApplication.class, args);
    }
}
