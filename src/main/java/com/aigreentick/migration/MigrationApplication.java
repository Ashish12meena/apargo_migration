package com.aigreentick.migration;

import com.aigreentick.migration.config.MigrationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

/**
 * One-off batch job: old aigreentick_2nd -> apargo_wa_messaging. No web server; runs the selected steps and exits
 * with 0 (success) or 1 (a step failed).
 */
@SpringBootApplication
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationApplication {

    public static void main(String[] args) {
        // all old TIMESTAMP / DATETIME values are handled as UTC wall-clock (JDBC connectionTimeZone=UTC too)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(MigrationApplication.class, args);
    }
}
