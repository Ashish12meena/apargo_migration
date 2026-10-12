package com.aigreentick.migration.core;

import com.aigreentick.migration.config.MigrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Runs the selected steps in order. Dry run = the whole run (bootstrap + all steps) in one transaction that is
 * rolled back at the end; constraint, ENUM and length errors still surface. Real run = each step commits per
 * batch / per row. Returns the process exit code.
 */
@Component
public class MigrationEngine {
    private static final Logger log = LoggerFactory.getLogger(MigrationEngine.class);

    private final MigrationProperties props;
    private final Db db;
    private final Sql sql;
    private final Tx tx;
    private final IdMap idMap;
    private final TenantResolver tenants;
    private final ProblemLog problems;
    private final Checkpoints checkpoints;
    private final RoleCatalog roles;
    private final UserRefs userRefs;
    private final Bootstrap bootstrap;
    private final RunRecorder recorder;

    public MigrationEngine(MigrationProperties props, Db db, Sql sql, Tx tx, IdMap idMap, TenantResolver tenants,
                           ProblemLog problems, Checkpoints checkpoints, RoleCatalog roles, UserRefs userRefs,
                           Bootstrap bootstrap, RunRecorder recorder) {
        this.props = props;
        this.db = db;
        this.sql = sql;
        this.tx = tx;
        this.idMap = idMap;
        this.tenants = tenants;
        this.problems = problems;
        this.checkpoints = checkpoints;
        this.roles = roles;
        this.userRefs = userRefs;
        this.bootstrap = bootstrap;
        this.recorder = recorder;
    }

    public int run(List<MigrationStep> allSteps) {
        List<MigrationStep> steps = select(allSteps);
        long runId = recorder.start(tx);
        problems.bind(tx, runId);
        log.info("=== migration run {} {}{}{}- steps: {}", runId, props.isDryRun() ? "[DRY RUN] " : "",
                props.isRefresh() ? "[REFRESH] " : "", props.isPilot() ? "[PILOT " + props.getTenants() + "] " : "",
                steps.stream().map(MigrationStep::id).toList());
        boolean[] validationFailed = {false};
        try {
            Runnable body = () -> {
                idMap.setCurrentStep("bootstrap");
                bootstrap.prepare();
                Scope scope = pilotScope();
                for (MigrationStep step : steps) {
                    if (!runStep(step, runId, scope)) validationFailed[0] = true;
                }
            };
            if (props.isDryRun()) {
                tx.rollbackOnly(body);
                log.info("=== DRY RUN finished: everything rolled back (mig_errors / mig_run rows of run {} kept)", runId);
            } else {
                body.run();
            }
            problems.flush();
            recorder.finish(tx, runId, validationFailed[0] ? "VALIDATION_FAILED" : "SUCCESS", null);
            log.info("=== run {} finished. Problems: SELECT step, severity, code, COUNT(*) FROM {}.mig_errors WHERE run_id = {} GROUP BY 1,2,3;",
                    runId, sql.migSchema(), runId);
            return validationFailed[0] && props.getValidate().isFailOnError() ? 1 : 0;
        } catch (RuntimeException e) {
            problems.flush();
            log.error("=== run {} FAILED: {}", runId, Tx.rootMessage(e), e);
            recorder.finish(tx, runId, "FAILED", truncate(Tx.rootMessage(e), 60000));
            return 1;
        }
    }

    /** @return false when a validation step reported failed checks */
    private boolean runStep(MigrationStep step, long runId, Scope scope) {
        log.info("--- {} - {}", step.id(), step.title());
        long t0 = System.currentTimeMillis();
        idMap.setCurrentStep(step.id());
        tenants.clearCache();
        problems.beginStep(step.id());
        if (props.isResetCheckpoints()) checkpoints.reset(step.id());
        StepContext ctx = new StepContext(step.id(), runId, props, db, sql, tx, idMap, tenants, problems, checkpoints,
                roles, userRefs, scope);
        boolean ok = true;
        try {
            step.run(ctx);
        } catch (ValidationFailed v) {
            ok = false;
        } finally {
            Map<String, long[]> perCode = problems.endStep();
            ctx.stats().all().values().forEach(e -> e.finishedAt = StepContext.now());
            recorder.saveStats(tx, runId, ctx.stats());
            ctx.stats().print(log);
            perCode.forEach((k, v) -> log.info("  problems {} : {}", k, v[0]));
            log.info("  ({} ms)", System.currentTimeMillis() - t0);
        }
        return ok;
    }

    private Scope pilotScope() {
        List<Long> ids = props.pilotTenantIds();
        if (ids.isEmpty()) return Scope.all();
        Set<Long> projects = new LinkedHashSet<>();
        for (Long id : ids) {
            Tenant t = tenants.resolve(id);
            if (!t.hasProject()) throw new IllegalArgumentException("pilot tenant " + id + " has no project: " + t.reason());
            projects.add(t.projectId());
        }
        log.info("  pilot projects: {}", projects);
        return Scope.of(projects);
    }

    private List<MigrationStep> select(List<MigrationStep> all) {
        Set<String> selected = props.selectedSteps();
        Set<String> skipped = props.skippedSteps();
        Set<String> known = new HashSet<>();
        all.forEach(s -> known.add(s.id()));
        for (String s : selected) if (!known.contains(s)) throw new IllegalArgumentException("unknown step id: " + s + " (known: " + new TreeSet<>(known) + ")");
        return all.stream()
                .filter(s -> selected.isEmpty() || selected.contains(s.id()))
                .filter(s -> !skipped.contains(s.id()))
                .sorted(Comparator.comparingInt(MigrationStep::order))
                .toList();
    }

    private static String truncate(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }

    /** Thrown by a validation step after recording failed checks; the run continues but ends VALIDATION_FAILED. */
    public static final class ValidationFailed extends RuntimeException {
        public ValidationFailed(String m) { super(m); }
    }
}
