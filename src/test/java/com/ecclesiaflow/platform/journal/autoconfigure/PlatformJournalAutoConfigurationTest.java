package com.ecclesiaflow.platform.journal.autoconfigure;

import com.ecclesiaflow.platform.journal.JournalIntegrity;
import com.ecclesiaflow.platform.journal.JournalPrivilegeException;
import com.ecclesiaflow.platform.journal.JournalReader;
import com.ecclesiaflow.platform.journal.JournalRepository;
import com.ecclesiaflow.platform.journal.JournalRetention;
import com.ecclesiaflow.platform.journal.PlatformJournal;
import com.ecclesiaflow.platform.journal.actuate.JournalHeadEndpoint;
import com.ecclesiaflow.platform.journal.events.JournalEventListener;
import com.ecclesiaflow.platform.journal.events.JournalMetrics;
import com.ecclesiaflow.platform.journal.jdbc.JdbcPlatformJournal;
import com.ecclesiaflow.platform.journal.jdbc.JournalPrivilegeCheck;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformJournalAutoConfigurationTest {

    private final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformJournalAutoConfiguration.class));

    private ApplicationContextRunner module(boolean superuser) {
        return bare
                .withBean(DataSource.class, () -> database(superuser))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    }

    private ApplicationContextRunner enabled() {
        return module(false).withPropertyValues("ecclesiaflow.journal.enabled=true",
                "ecclesiaflow.journal.module=members");
    }

    /** A database whose answers to the privilege check are those of a plain role, or of a superuser. */
    private static DataSource database(boolean superuser) {
        try {
            Map<String, Object> role = new LinkedHashMap<>();
            role.put("role_name", "members_app");
            role.put("superuser", superuser);
            role.put("schema_present", true);
            role.put("owner_member", false);
            role.put("schema_create", false);
            role.put("installed", true);
            Map<String, Object> privileges = new LinkedHashMap<>();
            privileges.put("line_insert", true);
            privileges.put("line_select", true);
            privileges.put("line_beyond", false);
            privileges.put("head_any", false);
            privileges.put("head_reader", true);
            privileges.put("purge", true);

            Statement statement = mock(Statement.class);
            // The role's query is the one that reads pg_roles; the other asks for the privileges.
            when(statement.executeQuery(anyString())).thenAnswer(call -> resultSet(
                    call.<String>getArgument(0).contains("pg_roles") ? role : privileges));
            Connection connection = mock(Connection.class);
            when(connection.createStatement()).thenReturn(statement);
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return dataSource;
        } catch (SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ResultSet resultSet(Map<String, Object> row) throws SQLException {
        List<String> columns = new ArrayList<>(row.keySet());
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(columns.size());
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getMetaData()).thenReturn(metaData);
        for (int i = 0; i < columns.size(); i++) {
            when(metaData.getColumnLabel(i + 1)).thenReturn(columns.get(i));
            when(resultSet.getObject(i + 1)).thenReturn(row.get(columns.get(i)));
        }
        return resultSet;
    }

    @Test
    @DisplayName("is registered for Spring Boot to find")
    void registered() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();

        assertThat(candidates).contains(PlatformJournalAutoConfiguration.class.getName());
    }

    @Test
    @DisplayName("off by default: a module that does not opt in gets no journal")
    void offByDefault() {
        module(false).run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(PlatformJournal.class)
                .doesNotHaveBean(JournalPrivilegeCheck.class));
    }

    @Nested
    @DisplayName("enabled")
    class Enabled {

        @Test
        @DisplayName("wires the writer, the reader, the verification, the purge, the listener, metrics and endpoint")
        void wires() {
            enabled().run(context -> assertThat(context).hasNotFailed()
                    .hasSingleBean(JournalPrivilegeCheck.class)
                    .hasSingleBean(JdbcPlatformJournal.class)
                    .hasSingleBean(JournalReader.class)
                    .hasSingleBean(JournalRepository.class)
                    .hasSingleBean(JournalIntegrity.class)
                    .hasSingleBean(JournalRetention.class)
                    .hasSingleBean(JournalEventListener.class)
                    .hasSingleBean(JournalMetrics.class)
                    .hasSingleBean(JournalHeadEndpoint.class));
        }

        @Test
        @DisplayName("a role the journal does not confine stops the module before anything can write a line")
        void unsafeRole() {
            module(true).withPropertyValues("ecclesiaflow.journal.enabled=true",
                    "ecclesiaflow.journal.module=members").run(context -> assertThat(context).hasFailed()
                    .getFailure().rootCause()
                    .isInstanceOf(JournalPrivilegeException.class)
                    .hasMessageContaining("superuser"));
        }

        @Test
        @DisplayName("without the module's name, or with one not in lower case, the module does not start")
        void moduleName() {
            module(false).withPropertyValues("ecclesiaflow.journal.enabled=true").run(context -> assertThat(context)
                    .hasFailed().getFailure().rootCause().hasMessageContaining("ecclesiaflow.journal.module"));
            module(false).withPropertyValues("ecclesiaflow.journal.enabled=true", "ecclesiaflow.journal.module=Members")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("without Micrometer or the actuator, the journal runs without metrics or endpoint")
        void optionalRuntimes() {
            enabled().withClassLoader(new FilteredClassLoader(MeterRegistry.class, Endpoint.class))
                    .run(context -> assertThat(context).hasNotFailed()
                            .hasSingleBean(PlatformJournal.class)
                            .doesNotHaveBean(JournalMetrics.class)
                            .doesNotHaveBean(JournalHeadEndpoint.class));
        }
    }
}
