package io.github.kmadsdev.consignado.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ETagsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"\"3\"|3", "W/\"7\"|7", "12|12", "\"abc\"|-1"})
    void parsesTheVersionFromIfMatch(String header, long expected) {
        assertThat(ETags.parseIfMatch(header)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"*", "  "})
    void noPreconditionMeansNull(String header) {
        assertThat(ETags.parseIfMatch(header)).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 5})
    void formatsAStrongETag(long version) {
        assertThat(ETags.of(version)).isEqualTo("\"" + version + "\"");
    }
}
