package io.github.kmadsdev.consignado.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kmadsdev.consignado.testsupport.TestCpfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CpfTest {

    @Test
    void computesCheckDigitsOfTheTextbookExample() {
        assertThat(Cpf.checkDigitsFor("123456789")).isEqualTo("09");
    }

    @Test
    void acceptsGeneratedCpfsWithAndWithoutPunctuation() {
        for (int i = 0; i < 500; i++) {
            String cpf = TestCpfs.random();

            assertThat(Cpf.isValid(cpf)).as(cpf).isTrue();
            assertThat(Cpf.of(TestCpfs.formatted(cpf)).value()).isEqualTo(cpf);
        }
    }

    @Test
    void rejectsAWrongCheckDigit() {
        for (int i = 0; i < 500; i++) {
            String cpf = TestCpfs.random();
            char last = cpf.charAt(10);
            String broken = cpf.substring(0, 10) + (char) ('0' + (last - '0' + 1) % 10);

            assertThat(Cpf.isValid(broken)).as(broken).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000000", "11111111111", "55555555555", "99999999999"})
    void rejectsRepeatedDigitsEvenThoughTheArithmeticPasses(String cpf) {
        assertThat(Cpf.isValid(cpf)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1234567890", "123456789012", "1234567890a", "abc.def.ghi-jk"})
    void rejectsMalformedInput(String raw) {
        assertThat(Cpf.isValid(raw)).isFalse();
        assertThatThrownBy(() -> Cpf.of(raw))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("invalid-cpf");
    }

    @Test
    void toStringNeverExposesTheFullNumber() {
        String raw = TestCpfs.random();
        Cpf cpf = Cpf.of(raw);

        assertThat(cpf.toString())
                .isEqualTo("***.***.***-" + raw.substring(9))
                .doesNotContain(raw.substring(0, 9));
        assertThat(cpf.masked()).isEqualTo(cpf.toString());
    }

    @Test
    void equalityIsByDigitsNotByFormatting() {
        String raw = TestCpfs.random();

        assertThat(Cpf.of(raw)).isEqualTo(Cpf.of(TestCpfs.formatted(raw)));
        assertThat(Cpf.of(raw)).hasSameHashCodeAs(Cpf.of(TestCpfs.formatted(raw)));
    }
}
