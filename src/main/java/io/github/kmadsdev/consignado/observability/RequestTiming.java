package io.github.kmadsdev.consignado.observability;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What one HTTP request did: its time in the application and its JDBC work. A recorder is
 * bound to the request thread by {@link RequestTimingFilter}, so work that is not a request
 * (the outbox relay, Flyway at start) finds no recorder and is not counted.
 *
 * <p>It only holds numbers. SQL text, parameters and entity data never reach it, so they
 * cannot reach a header or a log line.
 */
public final class RequestTiming {

    /** Statement kinds, from the first keyword of the SQL. A {@code SELECT ... FOR UPDATE} is a select. */
    public enum Kind { SELECT, INSERT, UPDATE, DELETE, OTHER }

    private static final ThreadLocal<RequestTiming> CURRENT = new ThreadLocal<>();
    private static final Pattern SAFE_INSTANCE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int FUNCTIONS_INSTANCE_LENGTH = 12;

    private final long startNanos;
    private final int[] counts = new int[Kind.values().length];
    private long statementNanos;
    private long connectionNanos;
    private int transactions;

    RequestTiming(long startNanos) {
        this.startNanos = startNanos;
    }

    /** Opens a recorder for the request on this thread. */
    static RequestTiming open() {
        RequestTiming timing = new RequestTiming(System.nanoTime());
        CURRENT.set(timing);
        return timing;
    }

    /** The recorder of the request on this thread, or null outside a request. */
    static RequestTiming current() {
        return CURRENT.get();
    }

    void close() {
        if (CURRENT.get() == this) {
            CURRENT.remove();
        }
    }

    void statement(Kind kind, int count, long nanos) {
        counts[kind.ordinal()] += count;
        statementNanos += nanos;
    }

    void connectionAcquired(long nanos) {
        connectionNanos += nanos;
    }

    void transactionEnded() {
        transactions++;
    }

    Snapshot snapshot() {
        return snapshot(System.nanoTime());
    }

    Snapshot snapshot(long nowNanos) {
        int statements = 0;
        for (int count : counts) {
            statements += count;
        }
        Snapshot.Db db = statements == 0 ? null : new Snapshot.Db(millis(statementNanos), statements,
                counts[Kind.SELECT.ordinal()], counts[Kind.INSERT.ordinal()], counts[Kind.UPDATE.ordinal()],
                counts[Kind.DELETE.ordinal()], counts[Kind.OTHER.ordinal()], millis(connectionNanos), transactions);
        return new Snapshot(millis(nowNanos - startNanos), db);
    }

    /**
     * The pod name on Kubernetes, the first 12 characters of the instance id on Azure
     * Functions, else {@code local}. A value that is not a short plain token is not used:
     * it goes into a header and a JSON string.
     */
    static String instanceName(Map<String, String> env) {
        String hostname = env.get("HOSTNAME");
        if (hostname != null && SAFE_INSTANCE.matcher(hostname).matches()) {
            return hostname;
        }
        String functions = env.get("WEBSITE_INSTANCE_ID");
        if (functions != null && !functions.isBlank()) {
            String shortId = functions.substring(0, Math.min(FUNCTIONS_INSTANCE_LENGTH, functions.length()));
            if (SAFE_INSTANCE.matcher(shortId).matches()) {
                return shortId;
            }
        }
        return "local";
    }

    /** Milliseconds with one decimal, rounded half up. */
    private static double millis(long nanos) {
        return Math.round(nanos / 100_000.0) / 10.0;
    }

    private static String format(double millis) {
        return String.format(Locale.ROOT, "%.1f", millis);
    }

    /** The numbers of a request at one moment, and the three ways they are written. */
    public record Snapshot(double appMs, Db db) {

        public record Db(double ms, int statements, int select, int insert, int update, int delete, int other,
                         double connectionMs, int transactions) {
        }

        /** The W3C {@code Server-Timing} value; {@code db} is left out when no statement ran. */
        public String serverTiming() {
            String app = "app;dur=" + format(appMs);
            if (db == null) {
                return app;
            }
            return app + ", db;dur=" + format(db.ms) + ";desc=\"" + db.statements
                    + (db.statements == 1 ? " statement\"" : " statements\"");
        }

        /** The one-line JSON of {@code X-Consignado-Timing}. {@code instance} is a plain token. */
        public String toJson(String instance) {
            StringBuilder json = new StringBuilder(200)
                    .append("{\"instance\":\"").append(instance).append("\",\"appMs\":").append(format(appMs))
                    .append(",\"db\":");
            if (db == null) {
                return json.append("null}").toString();
            }
            return json.append("{\"ms\":").append(format(db.ms))
                    .append(",\"statements\":").append(db.statements)
                    .append(",\"select\":").append(db.select)
                    .append(",\"insert\":").append(db.insert)
                    .append(",\"update\":").append(db.update)
                    .append(",\"delete\":").append(db.delete)
                    .append(",\"other\":").append(db.other)
                    .append(",\"connectionMs\":").append(format(db.connectionMs))
                    .append(",\"transactions\":").append(db.transactions)
                    .append("}}").toString();
        }

        /** Fields for the request log line. */
        public String logFields() {
            String app = "appMs=" + format(appMs);
            if (db == null) {
                return app + " db=none";
            }
            return app + " dbMs=" + format(db.ms) + " statements=" + db.statements + " select=" + db.select
                    + " insert=" + db.insert + " update=" + db.update + " delete=" + db.delete
                    + " other=" + db.other + " connectionMs=" + format(db.connectionMs)
                    + " transactions=" + db.transactions;
        }
    }
}
