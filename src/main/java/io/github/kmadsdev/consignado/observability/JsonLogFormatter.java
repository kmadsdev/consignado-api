package io.github.kmadsdev.consignado.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * One JSON object per line, with a small fixed set of fields. Plugged in through Spring
 * Boot's structured logging ({@code logging.structured.format.console}), so no extra
 * logging library is needed and the shape does not follow any one vendor's schema.
 */
public class JsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private final JsonWriter<ILoggingEvent> writer = JsonWriter.<ILoggingEvent>of(members -> {
        members.add("timestamp", event -> event.getInstant().toString());
        members.add("level", event -> event.getLevel().toString());
        members.add("logger", event -> event.getLoggerName());
        members.add("thread", event -> event.getThreadName());
        members.add("message", event -> event.getFormattedMessage());
        members.add("correlationId", event -> event.getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY))
                .whenNotNull();
        members.add("exception", event -> event.getThrowableProxy())
                .whenNotNull()
                .as(ThrowableProxyUtil::asString);
    }).withNewLineAtEnd();

    @Override
    public String format(ILoggingEvent event) {
        return writer.writeToString(event);
    }
}
