package io.github.kmadsdev.consignado.domain;

/** A contract was asked to move to a status that its current status does not allow. */
public class IllegalStatusTransitionException extends DomainException {

    private final ContractStatus from;
    private final ContractStatus to;

    public IllegalStatusTransitionException(ContractStatus from, ContractStatus to) {
        super("illegal-status-transition", "A contract in status " + from + " cannot move to " + to);
        this.from = from;
        this.to = to;
    }

    public ContractStatus from() {
        return from;
    }

    public ContractStatus to() {
        return to;
    }
}
