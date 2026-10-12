package com.aigreentick.migration.core;

import com.aigreentick.migration.config.MigrationProperties;
import org.springframework.stereotype.Component;

/** Schema-qualified table names. Every SQL statement of the service goes through here (single DataSource, no default schema). */
@Component
public class Sql {
    private final MigrationProperties props;

    public Sql(MigrationProperties props) { this.props = props; }

    /** table in the OLD monolith schema (read only) */
    public String old(String table) { return q(props.getSchemas().getOld(), table); }

    /** table in the NEW schema (all services) */
    public String tgt(String table) { return q(props.getSchemas().getTarget(), table); }

    /** table in the service's own tracking schema */
    public String mig(String table) { return q(props.getSchemas().getMig(), table); }

    public String oldSchema() { return props.getSchemas().getOld(); }
    public String tgtSchema() { return props.getSchemas().getTarget(); }
    public String migSchema() { return props.getSchemas().getMig(); }

    private static String q(String schema, String table) {
        return "`" + schema + "`.`" + table + "`";
    }
}
