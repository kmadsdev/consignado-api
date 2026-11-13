# ADR 0002: Money, rounding and what is left out (IOF, CET)

- Status: accepted
- Date: 2026-11-13

## Context

A loan schedule adds up many rounded values. If rounding is not explicit, the sum of the amortizations does not match the principal, or it matches only by luck. `double` cannot represent most cent values exactly.

## Decision

1. All amounts are `BigDecimal` with scale 2. Rates are fractions with scale 6 (`0.018500` is 1.85% per month).
2. Intermediate math uses `MathContext.DECIMAL128` (34 significant digits). Only stored values are rounded.
3. Rounding is `HALF_EVEN` (banker's rounding). Over many installments, `HALF_UP` pushes errors in one direction; `HALF_EVEN` does not.
4. Price (French) system: `PMT = P * i * (1+i)^n / ((1+i)^n - 1)`, rounded to cents. Interest of each period is `round(balance * i)`. Amortization is `PMT - interest`.
5. **The last installment absorbs the remainder.** It amortizes the whole remaining balance, so the balance ends at exactly 0.00 and the sum of amortizations equals the principal to the cent.
   The remainder is not always a few cents. Each regular line carries up to half a cent of PMT rounding and half a cent of interest rounding, and that error earns interest until the end: `|last - PMT| <= 0.01 * ((1+i)^(n-1) - 1) / i`. At 1.85% a month the bound is about R$ 1.93 for 84 months and R$ 4.25 for 120 months, but it grows fast with long terms (R$ 15,000.00 over 84 months: PMT 353.24, last 354.04).
   - The **margin is checked against the largest installment** of the schedule, not only PMT, in the simulation and again on approval.
   - When the rounded PMT would pay the loan off before the last month (a small amount over a long term, for example R$ 100.00 at 0% over 420 months), the simulation is rejected with 422 `term-too-long-for-amount`.
6. The **margin cap rounds DOWN**, not HALF_EVEN. The margin is a legal ceiling: rounding it up by one cent would allow an installment above the limit.
7. Due dates are computed from the first due date with `plusMonths(n - 1)`, not by chaining. A loan that starts on 31 January is due on 28 February and then on 31 March again, not on the 28th forever.

## Not built: IOF and CET

I left out IOF (the tax on credit operations) and CET (the total effective cost) on purpose:

- IOF on a loan to an individual has a fixed part on the amount and a daily part that depends on the number of days until each amortization, with a cap. When IOF is financed, it changes the principal, which changes the schedule. Doing this correctly needs exact day counts, the current rates and a decision about financing.
- CET is the internal rate of return of the real cash flows (amount released vs. installments on their real dates). It needs a numeric root finder and the IOF above.

A wrong CET is worse than no CET in a lending product, so the API does not return either. The simulation response shows principal, rate, installment, schedule and total payable. Adding both is the first item in the README's "what is not built" list.

## Consequences

- `PriceAmortizationTest` checks a hand-computed 3-month schedule line by line, the HALF_EVEN tie case (`101.505 -> 101.50`), a zero rate, end-of-month dates, the early-payoff rejection, and invariants over several schedules up to 420 months, with the remainder bound above.
- Committed margin (the sum over a customer's APPROVED and ACTIVE contracts) still adds up their regular PMT, which is the amount deducted in every month but the last one. A contract in its final month can deduct its remainder on top; that is small next to the margin and accepted here.
