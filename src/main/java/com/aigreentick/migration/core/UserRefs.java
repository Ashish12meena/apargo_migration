package com.aigreentick.migration.core;

import com.aigreentick.migration.config.MigrationProperties;
import com.aigreentick.migration.config.MigrationProperties.UserRefFormat;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Contact-service user columns are VARCHAR(64) (created_by, assignee_id, added_by, ...). Q-S9: they hold either
 * the numeric user id as text or the user's uuid; {@code migration.contacts.user-ref} decides.
 */
@Component
public class UserRefs {
    private final Db db;
    private final Sql sql;
    private final MigrationProperties props;
    private final Map<Long, String> uuids = new HashMap<>();

    public UserRefs(Db db, Sql sql, MigrationProperties props) {
        this.db = db;
        this.sql = sql;
        this.props = props;
    }

    public String ref(Long newUserId) {
        if (newUserId == null) return null;
        if (props.getContacts().getUserRef() == UserRefFormat.ID) return String.valueOf(newUserId);
        return uuids.computeIfAbsent(newUserId, id -> {
            Row r = db.row("SELECT uuid FROM " + sql.tgt("users") + " WHERE id = :id", Map.of("id", id));
            return r == null ? null : r.str("uuid");
        });
    }

    /** SQL expression for the same reference, given a column holding the new user id. */
    public String refSql(String newUserIdColumn) {
        if (props.getContacts().getUserRef() == UserRefFormat.ID) return "CAST(" + newUserIdColumn + " AS CHAR)";
        return "(SELECT uu.uuid FROM " + sql.tgt("users") + " uu WHERE uu.id = " + newUserIdColumn + ")";
    }
}
