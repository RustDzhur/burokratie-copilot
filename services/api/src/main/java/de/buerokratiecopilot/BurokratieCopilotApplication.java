package de.buerokratiecopilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the Bürokratie-Copilot modular monolith.
 *
 * <p>The application is a single deployable unit with strict package-level module boundaries
 * (see {@code docs/architecture.md}). The very same artifact can be started as a background
 * worker by activating the {@code worker} profile, which disables the web server and only
 * runs job consumers — this satisfies the "separate worker components" requirement of the
 * specification without duplicating the domain code.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BurokratieCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(BurokratieCopilotApplication.class, args);
    }
}
