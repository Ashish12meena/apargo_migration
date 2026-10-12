package com.aigreentick.migration.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/** Boot entry point: runs the engine once and exits the JVM with its exit code. */
@Component
public class StepRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StepRunner.class);

    private final MigrationEngine engine;
    private final List<MigrationStep> steps;
    private final ConfigurableApplicationContext context;

    public StepRunner(MigrationEngine engine, List<MigrationStep> steps, ConfigurableApplicationContext context) {
        this.engine = engine;
        this.steps = steps;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code;
        try {
            code = engine.run(steps);
        } catch (RuntimeException e) {
            log.error("migration could not start: {}", e.getMessage(), e);
            code = 1;
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}
