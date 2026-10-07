package com.ecclesiaflow.platform.events.outbox.jdbc;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/** A PostgreSQL server holding the outbox table exactly as the library ships it to the modules. */
final class OutboxPostgres {

    private OutboxPostgres() {
    }

    static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }

    // A connection per call, so concurrent relays really hold separate transactions.
    static DataSource dataSource(PostgreSQLContainer<?> postgres) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static void createTable(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(OutboxDdl.RESOURCE)).execute(dataSource);
    }

    /** What the auto-configuration gives the relay's repository. */
    static TransactionTemplate requiresNew(DataSource dataSource) {
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactions;
    }
}
