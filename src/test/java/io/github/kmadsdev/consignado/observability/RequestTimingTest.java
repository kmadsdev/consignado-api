package io.github.kmadsdev.consignado.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kmadsdev.consignado.observability.RequestTiming.Kind;
import io.github.kmadsdev.consignado.observability.RequestTiming.Snapshot;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RequestTimingTest {

    private static final long MS = 1_000_000L;

    @AfterEach
    void closeLeftovers() {
        RequestTiming open = RequestTiming.current();
        if (open != null) {
            open.close();
        }
    }

    @Test
    void aRequestWithoutStatementsHasNoDatabaseBlock() {
        RequestTiming timing = new RequestTiming(0);

        Snapshot snapshot = timing.snapshot(12_340_000L);

        assertThat(snapshot.db()).isNull();
        assertThat(snapshot.serverTiming()).isEqualTo("app;dur=12.3");
        assertThat(snapshot.toJson("local")).isEqualTo("{\"instance\":\"local\",\"appMs\":12.3,\"db\":null}");
        assertThat(snapshot.logFields()).isEqualTo("appMs=12.3 db=none");
    }

    @Test
    void countsStatementsByKindAndSumsTheirTime() {
        RequestTiming timing = new RequestTiming(0);
        timing.statement(Kind.SELECT, 1, 10 * MS);
        timing.statement(Kind.SELECT, 1, 8_700_000L);
        timing.statement(Kind.INSERT, 1, 10 * MS);
        timing.connectionAcquired(1_200_000L);
        timing.transactionEnded();

        Snapshot snapshot = timing.snapshot(41_300_000L);

        assertThat(snapshot.serverTiming()).isEqualTo("app;dur=41.3, db;dur=28.7;desc=\"3 statements\"");
        assertThat(snapshot.toJson("consignado-api-5d897fb475-ffmb4")).isEqualTo(
                "{\"instance\":\"consignado-api-5d897fb475-ffmb4\",\"appMs\":41.3,"
                        + "\"db\":{\"ms\":28.7,\"statements\":3,\"select\":2,\"insert\":1,\"update\":0,\"delete\":0,"
                        + "\"other\":0,\"connectionMs\":1.2,\"transactions\":1}}");
        assertThat(snapshot.logFields()).isEqualTo("appMs=41.3 dbMs=28.7 statements=3 select=2 insert=1 update=0 "
                + "delete=0 other=0 connectionMs=1.2 transactions=1");
    }

    @Test
    void oneStatementIsSingularAndABatchCountsEveryRow() {
        RequestTiming single = new RequestTiming(0);
        single.statement(Kind.UPDATE, 1, 50_000L);
        assertThat(single.snapshot(MS).serverTiming()).isEqualTo("app;dur=1.0, db;dur=0.1;desc=\"1 statement\"");

        RequestTiming batch = new RequestTiming(0);
        batch.statement(Kind.INSERT, 3, 2 * MS);
        batch.statement(Kind.DELETE, 1, MS);
        batch.statement(Kind.OTHER, 1, MS);
        Snapshot.Db db = batch.snapshot(10 * MS).db();
        assertThat(db.statements()).isEqualTo(5);
        assertThat(db.insert()).isEqualTo(3);
        assertThat(db.delete()).isEqualTo(1);
        assertThat(db.other()).isEqualTo(1);
        assertThat(db.ms()).isEqualTo(4.0);
    }

    @Test
    void connectionWaitAloneDoesNotMakeADatabaseBlock() {
        RequestTiming timing = new RequestTiming(0);
        timing.connectionAcquired(MS);
        timing.transactionEnded();

        assertThat(timing.snapshot(2 * MS).db()).isNull();
    }

    @Test
    void openBindsTheRecorderToTheThreadAndCloseUnbindsIt() {
        assertThat(RequestTiming.current()).isNull();

        RequestTiming timing = RequestTiming.open();
        assertThat(RequestTiming.current()).isSameAs(timing);

        timing.close();
        assertThat(RequestTiming.current()).isNull();
    }

    @Test
    void anotherThreadSeesNoRecorder() throws Exception {
        RequestTiming.open();
        RequestTiming[] seen = new RequestTiming[1];
        Thread other = new Thread(() -> seen[0] = RequestTiming.current());
        other.start();
        other.join();

        assertThat(seen[0]).isNull();
    }

    @Test
    void instanceIsThePodNameThenTheFunctionsInstanceThenLocal() {
        assertThat(RequestTiming.instanceName(Map.of("HOSTNAME", "consignado-api-5d897fb475-ffmb4",
                "WEBSITE_INSTANCE_ID", "abcdef0123456789"))).isEqualTo("consignado-api-5d897fb475-ffmb4");
        assertThat(RequestTiming.instanceName(Map.of("WEBSITE_INSTANCE_ID", "abcdef0123456789abcdef")))
                .isEqualTo("abcdef012345");
        assertThat(RequestTiming.instanceName(Map.of("WEBSITE_INSTANCE_ID", "short"))).isEqualTo("short");
        assertThat(RequestTiming.instanceName(Map.of("HOSTNAME", " "))).isEqualTo("local");
        assertThat(RequestTiming.instanceName(Map.of())).isEqualTo("local");
    }

    @Test
    void anInstanceNameThatIsNotAPlainTokenIsReplaced() {
        assertThat(RequestTiming.instanceName(Map.of("HOSTNAME", "pod\"},{\"x\":1"))).isEqualTo("local");
    }
}
