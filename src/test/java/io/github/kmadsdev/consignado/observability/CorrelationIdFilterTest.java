package io.github.kmadsdev.consignado.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CorrelationIdFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc-123", "4f1c2b8e-0d1a-4c55-9a52-7a8d4e3b2f10", "trace.id_01"})
    void keepsASafeCallerId(String header) {
        assertThat(CorrelationIdFilter.resolve(header)).isEqualTo(header);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has space", "line\nbreak", "{\"json\":1}",
            "0123456789012345678901234567890123456789012345678901234567890123456789"})
    void replacesAnythingElseWithANewUuid(String header) {
        String resolved = CorrelationIdFilter.resolve(header);

        assertThat(resolved).isNotEqualTo(header);
        assertThat(UUID.fromString(resolved)).isNotNull();
    }
}
