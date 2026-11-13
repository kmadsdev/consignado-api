package io.github.kmadsdev.consignado.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonLogFormatterTest {

    private final JsonLogFormatter formatter = new JsonLogFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final Logger logger = new LoggerContext().getLogger("test.logger");

    @Test
    void writesOneJsonObjectPerLineWithTheCorrelationId() throws Exception {
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.INFO, "contract {} approved", null,
                new Object[]{"c-1"});
        event.setMDCPropertyMap(Map.of(CorrelationIdFilter.MDC_KEY, "req-123"));

        String line = formatter.format(event);

        assertThat(line).endsWith("\n").doesNotContain("\n{");
        JsonNode node = json.readTree(line);
        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(node.get("logger").asText()).isEqualTo("test.logger");
        assertThat(node.get("message").asText()).isEqualTo("contract c-1 approved");
        assertThat(node.get("correlationId").asText()).isEqualTo("req-123");
        assertThat(node.get("timestamp").asText()).isNotBlank();
        assertThat(node.has("exception")).isFalse();
    }

    @Test
    void includesTheStackTraceAsOneFieldAndEscapesNewLines() throws Exception {
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.ERROR, "boom", new IllegalStateException("bad"), null);
        event.setMDCPropertyMap(Map.of());

        String line = formatter.format(event);

        assertThat(line.strip()).doesNotContain("\n");
        JsonNode node = json.readTree(line);
        assertThat(node.get("exception").asText()).contains("IllegalStateException: bad");
        assertThat(node.has("correlationId")).isFalse();
    }
}
