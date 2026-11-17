package io.github.kmadsdev.consignado.observability;

import io.github.kmadsdev.consignado.observability.RequestTiming.Kind;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.StatementType;
import net.ttddyy.dsproxy.listener.MethodExecutionContext;
import net.ttddyy.dsproxy.listener.MethodExecutionListener;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

/**
 * Feeds the JDBC work of the current request into its {@link RequestTiming}: each executed
 * statement (kind and time), the wait for a pooled connection, and each commit or rollback.
 * The SQL text is read only to find its first keyword; it is never stored or logged.
 *
 * <p>Outside a request (no recorder on the thread) every method returns at once.
 */
final class JdbcTimingListener implements QueryExecutionListener, MethodExecutionListener {

    private static final String START = JdbcTimingListener.class.getName() + ".start";
    private static final Kind[] KEYWORDS = {Kind.SELECT, Kind.INSERT, Kind.UPDATE, Kind.DELETE};

    @Override
    public void beforeQuery(ExecutionInfo execution, List<QueryInfo> queries) {
        if (RequestTiming.current() != null) {
            execution.addCustomValue(START, System.nanoTime());
        }
    }

    @Override
    public void afterQuery(ExecutionInfo execution, List<QueryInfo> queries) {
        RequestTiming timing = RequestTiming.current();
        Long start = execution.getCustomValue(START, Long.class);
        if (timing == null || start == null) {
            return;
        }
        long nanos = System.nanoTime() - start;
        for (QueryInfo query : queries) {
            timing.statement(kind(query.getQuery()), rows(execution, query), nanos);
            nanos = 0; // one execution, one time: a batch of several queries is not counted twice
        }
    }

    @Override
    public void beforeMethod(MethodExecutionContext context) {
        if (RequestTiming.current() != null && isGetConnection(context)) {
            context.addCustomValue(START, System.nanoTime());
        }
    }

    @Override
    public void afterMethod(MethodExecutionContext context) {
        RequestTiming timing = RequestTiming.current();
        if (timing == null) {
            return;
        }
        Long start = context.getCustomValue(START, Long.class);
        if (start != null) {
            timing.connectionAcquired(System.nanoTime() - start);
        } else if (isTransactionEnd(context)) {
            timing.transactionEnded();
        }
    }

    /** The first keyword after leading blanks and comments. A CTE ({@code with ...}) is other. */
    static Kind kind(String sql) {
        int i = 0;
        int length = sql == null ? 0 : sql.length();
        while (i < length) {
            if (Character.isWhitespace(sql.charAt(i))) {
                i++;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? length : end + 1;
            } else {
                break;
            }
        }
        for (Kind kind : KEYWORDS) {
            String keyword = kind.name();
            if (sql != null && sql.regionMatches(true, i, keyword, 0, keyword.length())
                    && (i + keyword.length() == length || !Character.isLetterOrDigit(sql.charAt(i + keyword.length())))) {
                return kind;
            }
        }
        return Kind.OTHER;
    }

    /** A prepared batch runs its query once per set of parameters. */
    private static int rows(ExecutionInfo execution, QueryInfo query) {
        if (execution.isBatch() && execution.getStatementType() != StatementType.STATEMENT) {
            return Math.max(1, query.getParametersList().size());
        }
        return 1;
    }

    private static boolean isGetConnection(MethodExecutionContext context) {
        return context.getTarget() instanceof DataSource && "getConnection".equals(context.getMethod().getName());
    }

    /** {@code commit()} or {@code rollback()} of a whole transaction (not to a savepoint). */
    private static boolean isTransactionEnd(MethodExecutionContext context) {
        String name = context.getMethod().getName();
        Object[] args = context.getMethodArgs();
        return context.getTarget() instanceof Connection && ("commit".equals(name) || "rollback".equals(name))
                && (args == null || args.length == 0) && context.getThrown() == null;
    }
}
