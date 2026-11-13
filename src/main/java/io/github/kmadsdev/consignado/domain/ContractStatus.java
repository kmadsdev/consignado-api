package io.github.kmadsdev.consignado.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a loan contract.
 *
 * <pre>
 * SIMULATED -> PROPOSED -> APPROVED -> ACTIVE -> SETTLED
 *                      \-> REJECTED        \-> CANCELLED
 *              PROPOSED, APPROVED -> CANCELLED
 * </pre>
 *
 * Every allowed edge is listed in one table. Anything not in the table is rejected.
 */
public enum ContractStatus {
    SIMULATED,
    PROPOSED,
    APPROVED,
    REJECTED,
    ACTIVE,
    SETTLED,
    CANCELLED;

    private static final Map<ContractStatus, Set<ContractStatus>> ALLOWED = new EnumMap<>(ContractStatus.class);

    static {
        ALLOWED.put(SIMULATED, EnumSet.of(PROPOSED));
        ALLOWED.put(PROPOSED, EnumSet.of(APPROVED, REJECTED, CANCELLED));
        ALLOWED.put(APPROVED, EnumSet.of(ACTIVE, CANCELLED));
        ALLOWED.put(ACTIVE, EnumSet.of(SETTLED, CANCELLED));
        ALLOWED.put(REJECTED, EnumSet.noneOf(ContractStatus.class));
        ALLOWED.put(SETTLED, EnumSet.noneOf(ContractStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(ContractStatus.class));
    }

    public boolean canTransitionTo(ContractStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Returns {@code target} when the move is allowed, otherwise throws. */
    public ContractStatus transitionTo(ContractStatus target) {
        if (target == null || !canTransitionTo(target)) {
            throw new IllegalStatusTransitionException(this, target);
        }
        return target;
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /**
     * Statuses whose installment is already reserved against the customer's margin.
     * A proposal does not reserve margin; approval does.
     */
    public static Set<ContractStatus> marginConsuming() {
        return EnumSet.of(APPROVED, ACTIVE);
    }
}
