package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalPrivilegeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JournalPrivilegeCheckTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private static Map<String, Object> role(boolean superuser, boolean schema, boolean ownerMember, boolean create,
                                            boolean installed) {
        Map<String, Object> row = new HashMap<>();
        row.put("role_name", "members_app");
        row.put("superuser", superuser);
        row.put("schema_present", schema);
        row.put("owner_member", ownerMember);
        row.put("schema_create", create);
        row.put("installed", installed);
        return row;
    }

    private static Map<String, Object> privileges(boolean insert, boolean select, boolean beyond, boolean head,
                                                  boolean reader, boolean purge) {
        return Map.of("line_insert", insert, "line_select", select, "line_beyond", beyond, "head_any", head,
                "head_reader", reader, "purge", purge);
    }

    private JournalPrivilegeCheck check() {
        return new JournalPrivilegeCheck(jdbcTemplate);
    }

    @Test
    @DisplayName("a plain role with INSERT and SELECT on the lines and the two functions starts")
    void plainRole() {
        when(jdbcTemplate.queryForMap(JournalPrivilegeCheck.ROLE_SQL)).thenReturn(role(false, true, false, false,
                true));
        when(jdbcTemplate.queryForMap(JournalPrivilegeCheck.PRIVILEGES_SQL))
                .thenReturn(privileges(true, true, false, false, true, true));

        assertThatCode(() -> check().afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a superuser is refused before any privilege is asked: every test would answer yes")
    void superuser() {
        when(jdbcTemplate.queryForMap(JournalPrivilegeCheck.ROLE_SQL)).thenReturn(role(true, true, true, true, true));

        assertThatThrownBy(() -> check().check())
                .isInstanceOf(JournalPrivilegeException.class)
                .hasMessageContaining("members_app")
                .hasMessageContaining("superuser");
        verify(jdbcTemplate, never()).queryForMap(JournalPrivilegeCheck.PRIVILEGES_SQL);
    }

    @Test
    @DisplayName("without the schema the module does not start")
    void noSchema() {
        when(jdbcTemplate.queryForMap(JournalPrivilegeCheck.ROLE_SQL)).thenReturn(role(false, false, false, false,
                false));

        assertThatThrownBy(() -> check().check())
                .isInstanceOf(JournalPrivilegeException.class)
                .hasMessageContaining("schema does not exist");
    }

    @Test
    @DisplayName("the schema's owner, or a member of it, is refused, and every problem is listed")
    void owner() {
        when(jdbcTemplate.queryForMap(JournalPrivilegeCheck.ROLE_SQL)).thenReturn(role(false, true, true, true,
                true));
        when(jdbcTemplate.queryForMap(JournalPrivilegeCheck.PRIVILEGES_SQL))
                .thenReturn(privileges(true, true, true, true, true, true));

        assertThatThrownBy(() -> check().check())
                .isInstanceOfSatisfying(JournalPrivilegeException.class, refused -> assertThat(refused.problems())
                        .containsExactly(
                                "owns the platform_journal schema, or is a member of its owner",
                                "may create objects in the platform_journal schema",
                                "holds UPDATE, DELETE, TRUNCATE, REFERENCES or TRIGGER on "
                                        + "platform_journal.journal_line",
                                "holds a privilege on platform_journal.journal_head"));
    }

    @Test
    @DisplayName("a journal not installed is refused without asking for privileges on objects that do not exist")
    void notInstalled() {
        when(jdbcTemplate.queryForMap(JournalPrivilegeCheck.ROLE_SQL)).thenReturn(role(false, true, false, false,
                false));

        assertThatThrownBy(() -> check().check())
                .isInstanceOf(JournalPrivilegeException.class)
                .hasMessageContaining("not installed");
        verify(jdbcTemplate, never()).queryForMap(JournalPrivilegeCheck.PRIVILEGES_SQL);
    }

    @Test
    @DisplayName("a role that could not write a line, or read the head, or purge, is refused too")
    void tooLittle() {
        when(jdbcTemplate.queryForMap(JournalPrivilegeCheck.ROLE_SQL)).thenReturn(role(false, true, false, false,
                true));
        when(jdbcTemplate.queryForMap(JournalPrivilegeCheck.PRIVILEGES_SQL))
                .thenReturn(privileges(false, true, false, false, true, false),
                        privileges(true, false, false, false, false, true));

        assertThatThrownBy(() -> check().check())
                .isInstanceOfSatisfying(JournalPrivilegeException.class, refused -> assertThat(refused.problems())
                        .containsExactly("lacks INSERT or SELECT on platform_journal.journal_line",
                                "lacks EXECUTE on platform_journal.current_head() or "
                                        + "platform_journal.purge_expired()"));
        assertThatThrownBy(() -> check().check()).isInstanceOf(JournalPrivilegeException.class);
    }
}
