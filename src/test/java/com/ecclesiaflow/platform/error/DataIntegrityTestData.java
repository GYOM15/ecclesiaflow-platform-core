package com.ecclesiaflow.platform.error;

import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

/**
 * Integrity violations as a JPA repository raises them on PostgreSQL: Spring's exception, Hibernate's
 * in between, the driver's SQLException with its SQLState at the root.
 */
public final class DataIntegrityTestData {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String FOREIGN_KEY_VIOLATION = "23503";
    public static final String NOT_NULL_VIOLATION = "23502";

    private DataIntegrityTestData() {
    }

    public static DataIntegrityViolationException uniqueViolation() {
        return violation(UNIQUE_VIOLATION,
                "ERROR: duplicate key value violates unique constraint \"uk_member_email\"");
    }

    public static DataIntegrityViolationException foreignKeyViolation() {
        return violation(FOREIGN_KEY_VIOLATION,
                "ERROR: insert or update on table \"church_membership\" violates foreign key constraint "
                        + "\"fk_membership_member\"");
    }

    public static DataIntegrityViolationException notNullViolation() {
        return violation(NOT_NULL_VIOLATION,
                "ERROR: null value in column \"church_id\" of relation \"event\" violates not-null constraint");
    }

    public static DataIntegrityViolationException violation(String sqlState, String driverMessage) {
        SQLException driver = new SQLException(driverMessage, sqlState);
        RuntimeException hibernate = new RuntimeException("could not execute statement", driver);
        return new DataIntegrityViolationException("could not execute statement [" + driverMessage + "]", hibernate);
    }
}
