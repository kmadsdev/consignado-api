# Product decisions

Why the platform has the limits that it has. Technical reasons are in [decisions.md](decisions.md).

Back to the [entry page](README.md).

## Who it is for

The platform is a technical demonstration of a cloud architecture. The signed-in user in mind is a recruiter, or an engineer who evaluates the author's work.

It is not a bank and not a product for borrowers. It makes no credit offer. All loan data is synthetic. The site says this on every page.

What the visitor should take away: the same API runs on a serverless host and on Kubernetes, every request is traced and drawn, and each trade-off is written down.

## Three access levels

| Level | What the person gets | Why |
|---|---|---|
| **Guest** | The site, the live diagram, a loan preview on the serverless host, a masked snapshot of the shared data. What the guest types stays in the browser. | A visitor with no account can see a real request run, with no access to the database and at almost no cost. |
| **Signed-in user** | The full API on the serverless host at all times, and the Kubernetes host inside access windows. | The Kubernetes host costs money per minute. An account gives a person to count the windows against, and a way to contact them. |
| **Admin** | The owner: all of it, with no window limit, plus the admin pages (users, sessions, engine, budget, contact requests). | Someone must approve held accounts, grant windows and stop the engine. |

Sign-up is self-service with an email one-time code. No password, no social login, no approval step for a normal account.

## Three windows of 15 minutes

The owner chose 3 windows of 15 minutes per account. The sources record the choice and not a written reason. The facts around it:

- **15 minutes** of node time cost about US$0.026. The countdown starts when the cluster is ready, not when the user asks.
- **3 windows** per account; more on request. A window is counted only when it opens; a failed start costs the user nothing.
- **The cluster stops at the end** and a 15-minute cool-down follows. A visitor who arrives in the cool-down sees the time left and can use the serverless host in the meantime.
- **The wait is shown, not hidden.** A stopped cluster needs minutes to start. The page draws each stage of the start from real signals.

## The request form for more

A user with no windows left sees a form: "Request more windows". The same form on the home page takes general questions. The owner reads each message and can grant 1 to 20 more windows to an account.

A grant is a decision of the owner. Nothing refills the windows automatically.

## Duplicate accounts

The rule is one person, one trial. A second account on the same device is held until the owner approves it. A second account from the same IP address only gets a flag, because an office or a university shares one address. The price is that a real person on a shared device can wait for an approval.

## The budget

- **US$60 per month**, on an "Azure for Students" subscription that is paid from a student credit.
- With the cluster stopped, the fixed cost is about US$31 to 35 per month (retail prices of 2026-11-17; some lines are estimates). The rest is room for windows and deploys.
- At 50 and 80 percent the owner gets an email. At 100 percent the engine is paused and no new window starts. The terms of the site say "until the next month"; the resume itself is a manual action of the owner in `/admin`.
- The pause touches only the Kubernetes host. The site, the diagram and the serverless host continue.
- The budget is an alert with an automatic action. It is not a hard spending cap.

## When the student credit ends

The real Kubernetes flow pauses. The site and the serverless flow stay.

In practice: the owner pauses the engine, so no window can start. The cluster stays stopped. Visitors still get the site, the live diagram of serverless requests, the guest preview, and the simulated replay of a cluster start, which the site marks "Simulation, not a real cluster".

The fixed costs of the stopped stack (database, registry, node disk, public IP) do not end with a pause. What happens to them after the credit ends is the owner's decision and is not settled in the sources of this page.

## What is deliberately not built

| Not built | Why |
|---|---|
| A cluster that runs all the time | About US$76 per month for the node alone, over the whole budget. |
| Azure Front Door and a managed WAF | Refused on this subscription type, and US$35 per month. |
| A private endpoint for the database | About US$8 per month plus a private DNS zone (estimate). The owner chose the cheaper path and wrote the risk down. |
| A separate identity tenant for visitors | Refused by the region policy of the subscription. |
| Passwords or social login | The sign-up flow uses an email one-time code only. |
| A login inside the API | The gateway owns identity. The API trusts the network. |
| Per-user data | All users share one synthetic data set. Nothing can be deleted through the API. |
| Automatic refill of windows | More windows are given by the owner, on request. |
| A hard spending cap | Azure budgets alert; they do not stop spend. The engine pause is the nearest safe action. |
| Deletion of old audit rows and unused accounts | Promised in the privacy notice, not yet automated. A known gap. |
| An always-ready serverless instance | The function apps run on demand only, so their cost stays inside the free grant or near it (estimate). The cold start is shown as it is. |
| A legal review of the privacy notice and terms | Not done. Stated as a gap. |

## Example

A hypothetical visit. A recruiter gets the link in a message and opens it on a phone between two meetings. As a guest, the recruiter runs one loan preview and watches the request travel through the diagram. That is the first visit, and it cost almost nothing. In the evening the recruiter signs up with an email code, starts a window, waits while the page shows the cluster come up, and calls the same API on Kubernetes. Two days later the recruiter wants to show it to a hiring manager and has two windows left. If the month's budget was reached in the meantime, the recruiter sees "the engine is paused" and still gets the serverless flow and the simulated replay.
