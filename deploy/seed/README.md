# Seed: synthetic demo data

`seed.sh` fills an empty consignado API with demo data. It uses only the public workflow
endpoints of `/api/v1`. It does not connect to the database, and it does not delete or edit
anything.

**All data is synthetic.** No person, salary, CPF or contract here is real.

## What it creates

| Type | Count | How |
|---|---|---|
| Agreements | 4 | `POST /agreements`: `DEMO-INSS`, `DEMO-ESTADO`, `DEMO-PREFEITURA`, `DEMO-EMPRESA` |
| Customers | 50 | `POST /customers`, spread over the 4 agreements |
| Simulations | 60 | `POST /simulations`: one for each customer, and a second one for customers 1 to 10 |
| Contracts | 30 | `POST /contracts` with `Idempotency-Key: seed-contract-NNN`, then the state endpoints |

Contract states at the end:

| State | Count | Calls after the proposal |
|---|---|---|
| `PROPOSED` (pending) | 5 | none |
| `APPROVED` | 5 | `approve` |
| `REJECTED` | 4 | `reject` |
| `ACTIVE` | 8 | `approve`, `activate` |
| `SETTLED` | 4 | `approve`, `activate`, `settle` |
| `CANCELLED` | 4 | `cancel` from `PROPOSED` (1), from `APPROVED` (1), from `ACTIVE` (2) |

The script also sends 3 simulations that are above the margin on purpose. The API refuses
them with `422 margin-exceeded` and stores nothing. The script expects that answer and
continues.

## Where the values come from

- **Deterministic.** Fixed lists and fixed formulas. No random value. Two runs on two empty
  databases give the same names, CPF numbers, salaries, amounts and states. The ids and the
  timestamps come from the API, so they change.
- **Names.** A first name, a middle name and a last name from three short lists of common
  Brazilian names, combined by position. They are invented combinations, not people.
- **CPF.** The 9-digit base of customer `i` is `111222333 + i * 13579246`. The script
  computes the two check digits with the official rule, so the API accepts the number.
  The numbers are not copied from any source and do not identify the demo "customers".
  The API returns the CPF masked (`***.***.***-NN`).
- **Net monthly salary.** `1800 + (i * 3571) mod 16201` reais, so R$ 1.800 to R$ 18.000.
- **Agreements.** Margin 30% or 35%, maximum term 60 to 96 months, rate 1.55% to 2.10% per
  month (sent as a fraction: `0.0155` to `0.0210`).
- **Margin rule.** A first loan is 2, 3 or 4 net salaries over 24 months or more. In the
  worst case (2.10%, 24 months, 4 salaries) the installment is about 21.4% of the salary.
  That is below the lowest margin (30%), so the API accepts each quote and each approval.

## Run

```sh
sh deploy/seed/seed.sh http://localhost:8080
```

In the cluster (the API has no public address), as a one-off pod that removes itself:

```sh
kubectl -n consignado run seed-$(date +%s) --rm -i --restart=Never \
  --image=curlimages/curl:8.11.1 --command -- \
  sh -s -- http://consignado-api.consignado.svc < deploy/seed/seed.sh
```

With `az aks command invoke`, add `--file deploy/seed/seed.sh` and read `seed.sh` from the
working directory of the command.

## Run it twice

The script first reads `GET /api/v1/agreements`. If the agreement `DEMO-INSS` exists, it
prints `already seeded`, changes nothing and exits with code 0.

## If it stops in the middle

A call that fails stops the script with an exit code that is not 0 and prints the HTTP
status and the response. The script retries only when no connection was made, so it never
sends a request twice. It does not resume: after a partial run the guard says
`already seeded`. The API has no delete, so the fix is a new database or a manual
completion through the API. Test on a local database first (`docker compose up --build`).

## Summary

The last lines are counts that the script reads back from the API: agreements, customers,
simulations, contracts, and contracts for each state.
