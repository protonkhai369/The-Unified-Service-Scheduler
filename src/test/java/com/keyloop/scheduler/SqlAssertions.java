package com.keyloop.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** Asserts that PostgreSQL itself rejected a statement, by SQLSTATE and constraint name. */
public final class SqlAssertions {

    private SqlAssertions() {
    }

    /** Asserts the statement fails with {@code sqlState} raised by constraint {@code constraint}. */
    public static void assertRejected(String sqlState, String constraint, ThrowingCallable statement) {
        ServerErrorMessage error = assertRejected(sqlState, statement);
        assertThat(error.getConstraint()).as("violated constraint").isEqualTo(constraint);
    }

    /** Asserts the statement fails with {@code sqlState}; returns the server's error for further checks. */
    public static ServerErrorMessage assertRejected(String sqlState, ThrowingCallable statement) {
        Throwable thrown = catchThrowable(statement);
        assertThat(thrown).as("statement should have been rejected by PostgreSQL").isNotNull();
        PSQLException psql = findPsqlException(thrown);
        assertThat(psql.getSQLState()).as("SQLSTATE of: %s", psql.getMessage()).isEqualTo(sqlState);
        return psql.getServerErrorMessage();
    }

    private static PSQLException findPsqlException(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return psql;
            }
        }
        throw new AssertionError("expected a PostgreSQL error in the cause chain", thrown);
    }
}
