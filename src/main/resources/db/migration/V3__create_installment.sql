-- The schedule is written once, when the contract becomes ACTIVE.
create table installment (
    id                uuid           primary key,
    contract_id       uuid           not null references loan_contract (id),
    number            integer        not null,
    due_date          date           not null,
    amount            numeric(15, 2) not null,
    interest          numeric(15, 2) not null,
    amortization      numeric(15, 2) not null,
    remaining_balance numeric(15, 2) not null,
    -- Also serves "where contract_id = ? order by number".
    constraint uk_installment_contract_number unique (contract_id, number),
    constraint ck_installment_amount check (amount = interest + amortization)
);
