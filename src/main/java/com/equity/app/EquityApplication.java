package com.equity.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Single Spring Boot application for the equity intraday momentum engine.
 *
 * <p><b>One module, package boundaries instead of build modules.</b> The layering is still real —
 * {@code domain} must not depend on Spring, {@code market} must not depend on {@code trading}, and
 * nothing outside {@code platform.time} may call {@code Instant.now()}. With a single module those
 * rules are enforced by ArchUnit at test time rather than by the build graph. That is a weaker
 * guarantee than separate modules (a violation fails the test run, not compilation) but it keeps
 * one build, one jar and one place to look — worth the trade while the design is still moving.</p>
 */
@SpringBootApplication(scanBasePackages = "com.equity")
@ConfigurationPropertiesScan(basePackages = "com.equity")
// scanBasePackages moves the COMPONENT scan but not these two: JPA defaults them to the package of
// this class, which would find neither the entities nor the repositories a package up.
@EntityScan("com.equity")
@EnableJpaRepositories("com.equity")
@EnableScheduling
public class EquityApplication {
    public static void main(String[] args) {
        SpringApplication.run(EquityApplication.class, args);
    }
}
