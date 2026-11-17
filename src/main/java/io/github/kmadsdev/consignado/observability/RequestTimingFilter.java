package io.github.kmadsdev.consignado.observability;

import io.github.kmadsdev.consignado.observability.RequestTiming.Snapshot;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tells the caller what the request did, in two response headers (docs/adr/0013):
 * {@code Server-Timing} (the W3C header browsers show) and {@code X-Consignado-Timing}
 * (one line of JSON for the gateway). Numbers only: no SQL, no parameter, no entity data.
 *
 * <p>Runs right inside {@link CorrelationIdFilter}, which adds the same numbers to its
 * request log line. Actuator requests are not measured.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestTimingFilter extends OncePerRequestFilter {

    public static final String SERVER_TIMING = "Server-Timing";
    public static final String HEADER = "X-Consignado-Timing";
    static final String SNAPSHOT_ATTRIBUTE = RequestTimingFilter.class.getName() + ".snapshot";

    private final String instance = RequestTiming.instanceName(System.getenv());
    private final ObjectProvider<MeterRegistry> registry;

    public RequestTimingFilter(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestTiming timing = RequestTiming.open();
        CommitAwareResponse wrapped = new CommitAwareResponse(response, () -> {
            Snapshot snapshot = timing.snapshot();
            response.setHeader(SERVER_TIMING, snapshot.serverTiming());
            response.setHeader(HEADER, snapshot.toJson(instance));
            request.setAttribute(SNAPSHOT_ATTRIBUTE, snapshot);
        });
        try {
            chain.doFilter(request, wrapped);
        } finally {
            wrapped.beforeCommit();
            timing.close();
            if (request.getAttribute(SNAPSHOT_ATTRIBUTE) instanceof Snapshot snapshot) {
                record(snapshot);
            }
        }
    }

    /** Statements per request always; database time only for requests that ran a statement. */
    private void record(Snapshot snapshot) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters == null) {
            return;
        }
        DistributionSummary.builder("consignado.db.request.statements")
                .description("JDBC statements run by one HTTP request")
                .baseUnit("statements")
                .register(meters)
                .record(snapshot.db() == null ? 0 : snapshot.db().statements());
        if (snapshot.db() != null) {
            Timer.builder("consignado.db.request.time")
                    .description("Sum of the JDBC statement times of one HTTP request")
                    .register(meters)
                    .record(Math.round(snapshot.db().ms() * 1_000_000), TimeUnit.NANOSECONDS);
        }
    }
}
