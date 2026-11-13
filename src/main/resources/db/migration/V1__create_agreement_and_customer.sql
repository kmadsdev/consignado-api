-- Employer agreement (convenio): the rules a payroll-deducted loan must follow for one employer.
create table agreement (
    id                    uuid           primary key,
    code                  varchar(40)    not null,
    name                  varchar(120)   not null,
    max_margin_percent    numeric(5, 2)  not null,
    max_term_months       integer        not null,
    monthly_interest_rate numeric(9, 6)  not null,
    version               bigint         not null,
    created_at            timestamptz    not null,
    constraint uk_agreement_code unique (code),
    constraint ck_agreement_margin check (max_margin_percent > 0 and max_margin_percent <= 100),
    constraint ck_agreement_term check (max_term_months between 1 and 420),
    constraint ck_agreement_rate check (monthly_interest_rate >= 0 and monthly_interest_rate < 1)
);

-- A customer is linked to one agreement (one employment) in this version.
create table customer (
    id                 uuid           primary key,
    cpf                varchar(11)    not null,
    full_name          varchar(160)   not null,
    agreement_id       uuid           not null references agreement (id),
    net_monthly_salary numeric(15, 2) not null,
    version            bigint         not null,
    created_at         timestamptz    not null,
    constraint uk_customer_cpf unique (cpf),
    constraint ck_customer_cpf_digits check (cpf ~ '^[0-9]{11}$'),
    constraint ck_customer_salary check (net_monthly_salary > 0)
);
