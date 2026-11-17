package io.github.kmadsdev.consignado.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.kmadsdev.consignado.observability.RequestTiming.Kind;
import io.github.kmadsdev.consignado.observability.RequestTiming.Snapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The proxy around a mocked driver: what a request's JDBC calls turn into. */
class JdbcTimingListenerTest {

    private final Connection connection = mock(Connection.class);
    private final DataSource proxy = proxyOver(connection);

    @AfterEach
    void closeRecorder() {
        RequestTiming open = RequestTiming.current();
        if (open != null) {
            open.close();
        }
    }

    @Test
    void recordsStatementsConnectionWaitAndTransactionsOfTheRequest() throws Exception {
        RequestTiming timing = RequestTiming.open();

        try (Connection c = proxy.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement select = c.prepareStatement("select c.id from customer c where c.id=? for update")) {
                select.executeQuery();
            }
            try (PreparedStatement insert = c.prepareStatement("insert into customer (id) values (?)")) {
                insert.setString(1, "a");
                insert.addBatch();
                insert.setString(1, "b");
                insert.addBatch();
                insert.executeBatch();
            }
            try (Statement other = c.createStatement()) {
                other.execute("  /* comment */ UPDATE customer set version = version + 1");
                other.execute("set local lock_timeout = '1s'");
            }
            c.rollback(mock(Savepoint.class));
            c.commit();
        }

        Snapshot.Db db = timing.snapshot().db();
        assertThat(db.select()).isEqualTo(1);
        assertThat(db.insert()).isEqualTo(2);
        assertThat(db.update()).isEqualTo(1);
        assertThat(db.other()).isEqualTo(1);
        assertThat(db.statements()).isEqualTo(5);
        assertThat(db.transactions()).isEqualTo(1);
        assertThat(db.ms()).isGreaterThanOrEqualTo(0.0);
        assertThat(db.connectionMs()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void workOutsideARequestIsNotCountedAndDoesNotFail() throws Exception {
        try (Connection c = proxy.getConnection();
             PreparedStatement statement = c.prepareStatement("delete from outbox_event where id=?")) {
            statement.executeUpdate();
            c.commit();
        }

        assertThat(RequestTiming.current()).isNull();
    }

    @Test
    void classifiesByTheFirstKeyword() {
        assertThat(JdbcTimingListener.kind("select 1")).isEqualTo(Kind.SELECT);
        assertThat(JdbcTimingListener.kind("DELETE from x")).isEqualTo(Kind.DELETE);
        assertThat(JdbcTimingListener.kind("with x as (select 1) select * from x")).isEqualTo(Kind.OTHER);
        assertThat(JdbcTimingListener.kind("-- note\n insert into x values (1)")).isEqualTo(Kind.INSERT);
        assertThat(JdbcTimingListener.kind("/* unclosed")).isEqualTo(Kind.OTHER);
        assertThat(JdbcTimingListener.kind("selection")).isEqualTo(Kind.OTHER);
        assertThat(JdbcTimingListener.kind("update")).isEqualTo(Kind.UPDATE);
        assertThat(JdbcTimingListener.kind(null)).isEqualTo(Kind.OTHER);
    }

    private static DataSource proxyOver(Connection connection) {
        try {
            DataSource pool = mock(DataSource.class);
            when(pool.getConnection()).thenReturn(connection);
            PreparedStatement prepared = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(prepared);
            when(prepared.executeBatch()).thenReturn(new int[] {1, 1});
            when(connection.createStatement()).thenReturn(mock(Statement.class));
            return JdbcTimingConfig.wrap(pool);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
