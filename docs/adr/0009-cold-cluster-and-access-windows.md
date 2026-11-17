# ADR 0009: A cold cluster and access windows

- Status: accepted
- Date: 2026-11-17

## Context

The subscription is "Azure for Students" and the budget is US$60 per month. One AKS node (`Standard_D2as_v4`) costs US$0.104 per hour. Left on for a month it is about US$76, more than the whole budget. The fixed cost with the cluster stopped is already about US$31 to 35 per month.

A visitor must still be able to see the API run on real Kubernetes, not on a recording.

AKS can stop and start a cluster. The Microsoft guidance says to wait 15 to 30 minutes between a stop and a start.

## Decision

**The cluster is stopped by default. A signed-in user starts it for one access window.**

- A window is requested with `POST /api/sessions`. The gateway checks the account (active, profile complete, windows left), the engine (not paused, not in cool-down), and then asks Azure Resource Manager to start the cluster if it is stopped.
- The gateway records the start as lifecycle events from real signals: the ARM power state, Kubernetes nodes, pod conditions and container states, Service endpoints, then one real `GET /actuator/health/readiness` through the internal load balancer.
- **The window opens only when that call answers 200.** It lasts 15 minutes from that moment. The page of the window is `https://cluster.consignado.kmads.dev/app/<id>/aks/`.
- **Each account gets 3 windows.** A window is counted when it opens, not when it is requested. A start that does not reach ready in 15 minutes is marked `failed`, costs the user nothing, and the cluster is stopped. The owner can grant more windows.
- A timer runs every minute. When no session is `starting` or `ready`, it stops the cluster.
- **After each stop, a cool-down of 15 minutes.** A request in that time gets 409 `cooldown` with the time left.
- The engine states are `stopped`, `starting`, `running`, `stopping`, `cooldown` and `paused`.
- **The gateway can only read, start and stop the cluster.** Its Azure role (`Consignado Engine Operator`) has no write and no delete, and the code has no call for them.
- The node uses a managed OS disk of 32 GB. It was not confirmed that a cluster with an ephemeral OS disk can be stopped and started, so the safe option was taken (US$5.81 per month).

## Consequences

- One window costs about US$0.026 of node time; 100 windows cost US$2.60. The node also runs during the start and the stop, so the real cost per window is higher. That extra was not measured.
- The first user waits minutes before the window opens. The page shows each stage of the start while the user waits; the wait is part of what the demonstration shows. The start time on this cluster was not measured.
- During the cool-down a second visitor is refused and must come back.
- The deploy job also starts the cluster. The gateway reads the real power state and gives a cluster that it did not start 20 minutes before the timer stops it, so a deploy is not cut in the middle.
- If the gateway is down, nothing stops a running cluster. The runbook has the manual command.
- Two windows of two users can overlap; the cluster stops when the last one ends.
- The whole flow (start, ready, countdown, stop, cool-down) was tested with fakes. It was not verified against the real cluster when this ADR was written.

## What a bank would add

- **A cluster that runs all the time**, with more than one node, zones, autoscaling and reserved capacity. A stopped cluster is a cost decision of a demonstration.
- **Scale to zero inside a running cluster** (for example event-driven autoscaling of the workload) where idle cost matters, in place of a stop of the control plane.
- **A queue for requests** in place of a refusal during the cool-down.
