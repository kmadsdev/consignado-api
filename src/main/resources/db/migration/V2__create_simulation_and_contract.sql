create table loan_simulation (
    id                    uuid           primary key,
    customer_id           uuid           not null references customer (id),
    agreement_id          uuid           not null references agreement (id),
    principal             numeric(15, 2) not null,
    monthly_interest_rate numeric(9, 6)  not null,
    term_months           integer        not null,
    installment_amount    numeric(15, 2) not null,
    total_payable         numeric(15, 2) not null,
    available_margin      numeric(15, 2) not null,
    created_at            timestamptz    not null,
    expires_at            timestamptz    not null,
    constraint ck_simulation_principal check (principal > 0),
    constraint ck_simulation_term check (term_months between 1 and 420)
);

create table loan_contract (
    id                    uuid           primary key,
    simulation_id         uuid           not null references loan_simulation (id),
    customer_id           uuid           not null references customer (id),
    status                varchar(20)    not null,
    principal             numeric(15, 2) not null,
    monthly_interest_rate numeric(9, 6)  not null,
    term_months           integer        not null,
    installment_amount    numeric(15, 2) not null,
    idempotency_key       varchar(64)    not null,
    status_reason         varchar(255),
    version               bigint         not null,
    created_at            timestamptz    not null,
    updated_at            timestamptz    not null,
    -- One simulation becomes at most one contract; one key creates at most one contract.
    constraint uk_contract_simulation unique (simulation_id),
    constraint uk_contract_idempotency_key unique (idempotency_key),
    constraint ck_contract_status check (status in
        ('SIMULATED', 'PROPOSED', 'APPROVED', 'REJECTED', 'ACTIVE', 'SETTLED', 'CANCELLED'))
);

-- Margin check: sum(installment_amount) where customer_id = ? and status in (...)
-- Listing by customer, optionally filtered by status.
create index ix_contract_customer_status on loan_contract (customer_id, status);

-- Back-office listing by status, newest first.
create index ix_contract_status_created on loan_contract (status, created_at desc);
