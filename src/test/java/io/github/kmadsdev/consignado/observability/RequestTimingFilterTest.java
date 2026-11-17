package io.github.kmadsdev.consignado.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kmadsdev.consignado.observability.RequestTiming.Kind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestTimingFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RequestTimingFilter filter = new RequestTimingFilter(provider(meters));
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/customers");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void headersAreSetBeforeTheBodyCommitsTheResponse() throws Exception {
        String[] seenAtCommit = new String[2];
        run((req, res) -> {
            RequestTiming.current().statement(Kind.INSERT, 1, 2_000_000L);
            res.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            res.flushBuffer();
            seenAtCommit[0] = response.getHeader(RequestTimingFilter.SERVER_TIMING);
            seenAtCommit[1] = response.getHeader(RequestTimingFilter.HEADER);
            RequestTiming.current().statement(Kind.SELECT, 1, 1_000_000L); // after the commit: not in the header
        });

        assertThat(response.isCommitted()).isTrue();
        assertThat(seenAtCommit[0]).matches("app;dur=\\d+\\.\\d, db;dur=2\\.0;desc=\"1 statement\"");
        assertThat(seenAtCommit[1]).contains("\"insert\":1").contains("\"select\":0").contains("\"ms\":2.0");
        assertThat(RequestTiming.current()).isNull();
    }

    @Test
    void anEmptyResponseStillGetsTheHeadersAndNoDatabaseBlock() throws Exception {
        run((req, res) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT));

        assertThat(response.getHeader(RequestTimingFilter.SERVER_TIMING)).matches("app;dur=\\d+\\.\\d");
        assertThat(response.getHeader(RequestTimingFilter.HEADER)).endsWith(",\"db\":null}");
        assertThat(meters.get("consignado.db.request.statements").summary().count()).isEqualTo(1);
        assertThat(meters.find("consignado.db.request.time").timer()).isNull();
    }

    @Test
    void aWriterBodyAndAnErrorAlsoCarryTheHeaders() throws Exception {
        run((req, res) -> {
            RequestTiming.current().statement(Kind.SELECT, 1, 1_000_000L);
            res.getWriter().write("{\"status\":404}");
        });
        assertThat(response.getHeader(RequestTimingFilter.HEADER)).contains("\"select\":1");
        assertThat(meters.get("consignado.db.request.time").timer().count()).isEqualTo(1);

        MockHttpServletResponse error = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/nothing"), error,
                (req, res) -> ((HttpServletResponse) res).sendError(404));
        assertThat(error.getHeader(RequestTimingFilter.SERVER_TIMING)).startsWith("app;dur=");
    }

    @Test
    void aResetBeforeTheCommitWritesTheHeadersAgain() throws Exception {
        run((req, res) -> {
            res.getWriter().write("partial");
            res.reset();
            RequestTiming.current().statement(Kind.UPDATE, 1, 1_000_000L);
            res.getWriter().write("{}");
        });

        assertThat(response.getHeader(RequestTimingFilter.HEADER)).contains("\"update\":1");
    }

    @Test
    void actuatorRequestsAreNotMeasured() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), response,
                (req, res) -> assertThat(RequestTiming.current()).isNull());

        assertThat(response.getHeader(RequestTimingFilter.HEADER)).isNull();
    }

    @Test
    void worksWithoutAMeterRegistry() throws Exception {
        new RequestTimingFilter(provider(null)).doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestTimingFilter.HEADER)).contains("\"db\":null");
    }

    private void run(Chain chain) throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            try {
                chain.run(req, (HttpServletResponse) res);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private interface Chain {
        void run(jakarta.servlet.ServletRequest request, HttpServletResponse response) throws java.io.IOException;
    }

    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (registry != null) {
            factory.addBean("meters", registry);
        }
        return factory.getBeanProvider(MeterRegistry.class);
    }
}
