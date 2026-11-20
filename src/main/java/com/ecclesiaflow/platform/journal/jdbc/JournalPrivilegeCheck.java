package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalPrivilegeException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Refuses to start a module whose role could rewrite its journal: PostgreSQL waives grants and triggers for a
 * superuser, a member of the schema's owner can become it, and {@code CREATE} on the schema would let the role shape
 * the objects that guard it. Also refuses a role that could not write it, which would fail every gesture later.
 */
public class JournalPrivilegeCheck implements InitializingBean {

    static final String ROLE_SQL = """
            SELECT r.rolname AS role_name,
                   r.rolsuper AS superuser,
                   n.oid IS NOT NULL AS schema_present,
                   COALESCE(pg_has_role(r.oid, n.nspowner, 'MEMBER'), false) AS owner_member,
                   COALESCE(has_schema_privilege(r.oid, n.oid, 'CREATE'), false) AS schema_create,
                   to_regclass('platform_journal.journal_line') IS NOT NULL
                       AND to_regclass('platform_journal.journal_head') IS NOT NULL
                       AND to_regprocedure('platform_journal.current_head()') IS NOT NULL
                       AND to_regprocedure('platform_journal.purge_expired()') IS NOT NULL AS installed
            FROM pg_roles r
            LEFT JOIN pg_namespace n ON n.nspname = 'platform_journal'
            WHERE r.rolname = current_user""";

    static final String PRIVILEGES_SQL = """
            SELECT has_table_privilege('platform_journal.journal_line', 'INSERT') AS line_insert,
                   has_table_privilege('platform_journal.journal_line', 'SELECT') AS line_select,
                   has_table_privilege('platform_journal.journal_line',
                                       'UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER') AS line_beyond,
                   has_table_privilege('platform_journal.journal_head',
                                       'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER') AS head_any,
                   has_function_privilege('platform_journal.current_head()', 'EXECUTE') AS head_reader,
                   has_function_privilege('platform_journal.purge_expired()', 'EXECUTE') AS purge""";

    private final JdbcTemplate jdbcTemplate;

    public JournalPrivilegeCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        check();
    }

    /** @throws JournalPrivilegeException listing every problem found */
    public void check() {
        Map<String, Object> role = jdbcTemplate.queryForMap(ROLE_SQL);
        String name = String.valueOf(role.get("role_name"));
        List<String> problems = new ArrayList<>();
        // A superuser passes every privilege test: that alone is the answer.
        if (flag(role, "superuser")) {
            throw new JournalPrivilegeException(name, List.of("is a superuser, which no grant or trigger confines"));
        }
        if (!flag(role, "schema_present")) {
            throw new JournalPrivilegeException(name, List.of("the platform_journal schema does not exist: the "
                    + "pre-boot step creates it, owned by the journal's own role"));
        }
        if (flag(role, "owner_member")) {
            problems.add("owns the platform_journal schema, or is a member of its owner");
        }
        if (flag(role, "schema_create")) {
            problems.add("may create objects in the platform_journal schema");
        }
        if (!flag(role, "installed")) {
            problems.add("the journal is not installed: run db/journal/platform_journal.sql as the schema's owner");
            throw new JournalPrivilegeException(name, problems);
        }
        Map<String, Object> privileges = jdbcTemplate.queryForMap(PRIVILEGES_SQL);
        if (!flag(privileges, "line_insert") || !flag(privileges, "line_select")) {
            problems.add("lacks INSERT or SELECT on platform_journal.journal_line");
        }
        if (flag(privileges, "line_beyond")) {
            problems.add("holds UPDATE, DELETE, TRUNCATE, REFERENCES or TRIGGER on platform_journal.journal_line");
        }
        if (flag(privileges, "head_any")) {
            problems.add("holds a privilege on platform_journal.journal_head");
        }
        if (!flag(privileges, "head_reader") || !flag(privileges, "purge")) {
            problems.add("lacks EXECUTE on platform_journal.current_head() or platform_journal.purge_expired()");
        }
        if (!problems.isEmpty()) {
            throw new JournalPrivilegeException(name, problems);
        }
    }

    private static boolean flag(Map<String, Object> row, String column) {
        return Boolean.TRUE.equals(row.get(column));
    }
}
