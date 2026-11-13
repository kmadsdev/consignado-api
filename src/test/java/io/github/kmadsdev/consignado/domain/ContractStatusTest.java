package io.github.kmadsdev.consignado.domain;

import static io.github.kmadsdev.consignado.domain.ContractStatus.ACTIVE;
import static io.github.kmadsdev.consignado.domain.ContractStatus.APPROVED;
import static io.github.kmadsdev.consignado.domain.ContractStatus.CANCELLED;
import static io.github.kmadsdev.consignado.domain.ContractStatus.PROPOSED;
import static io.github.kmadsdev.consignado.domain.ContractStatus.REJECTED;
import static io.github.kmadsdev.consignado.domain.ContractStatus.SETTLED;
import static io.github.kmadsdev.consignado.domain.ContractStatus.SIMULATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class ContractStatusTest {

    /** The specification, written independently from the enum's own table. */
    private static final Map<ContractStatus, Set<ContractStatus>> EXPECTED = Map.of(
            SIMULATED, Set.of(PROPOSED),
            PROPOSED, Set.of(APPROVED, REJECTED, CANCELLED),
            APPROVED, Set.of(ACTIVE, CANCELLED),
            ACTIVE, Set.of(SETTLED, CANCELLED),
            REJECTED, Set.of(),
            SETTLED, Set.of(),
            CANCELLED, Set.of());

    static Stream<Arguments> everyPair() {
        return Stream.of(ContractStatus.values())
                .flatMap(from -> Stream.of(ContractStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void allowsExactlyTheSpecifiedTransitions(ContractStatus from, ContractStatus to) {
        boolean allowed = EXPECTED.get(from).contains(to);

        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
        if (allowed) {
            assertThat(from.transitionTo(to)).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> from.transitionTo(to))
                    .isInstanceOfSatisfying(IllegalStatusTransitionException.class, ex -> {
                        assertThat(ex.from()).isEqualTo(from);
                        assertThat(ex.to()).isEqualTo(to);
                        assertThat(ex.code()).isEqualTo("illegal-status-transition");
                    });
        }
    }

    @ParameterizedTest
    @EnumSource(ContractStatus.class)
    void noStatusCanMoveToItself(ContractStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @Test
    void terminalStatusesAreRejectedSettledAndCancelled() {
        assertThat(Stream.of(ContractStatus.values()).filter(ContractStatus::isTerminal))
                .containsExactlyInAnyOrder(REJECTED, SETTLED, CANCELLED);
    }

    @Test
    void nullTargetIsRejected() {
        assertThatThrownBy(() -> PROPOSED.transitionTo(null)).isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    void onlyApprovedAndActiveContractsConsumeMargin() {
        assertThat(ContractStatus.marginConsuming()).containsExactlyInAnyOrder(APPROVED, ACTIVE);
    }
}
