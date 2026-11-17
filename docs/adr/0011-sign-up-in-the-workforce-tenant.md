# ADR 0011: Sign-up in the owner's own Entra ID tenant

- Status: accepted
- Date: 2026-11-17
- Updated: 2026-11-17, after the first live day

## Context

A recruiter must be able to make an account alone, with no password to invent. The platform must know one stable id per person, to count access windows.

The usual product for this is a separate Microsoft Entra External ID tenant: a directory only for customers, apart from the directory that manages the subscription. The attempt to create one was refused by the region policy of the subscription.

## Decision

**Recruiters sign up in the owner's own tenant, as guest users, through a B2B self-service sign-up user flow.**

- The user flow is `B2X_1_consignado`. Identity providers: Entra ID accounts and **email one-time code**. Microsoft personal accounts are not selected. The only attributes collected are the display name and the email address.
- The app registration of the site, `consignado-spa`, is linked to the user flow. It is a single-page application with no client secret. The gateway has its own registration, `consignado-api-gateway`, which exposes the scope `access_as_user` and issues version 2 tokens.
- **The tenant was hardened for guests** before the flow was opened: the guest user role was set from "limited" to the most restricted level, and invitations were limited to admins and guest inviters.
- Company, LinkedIn URL and consent are not in Entra ID. The user gives them in a profile form after the first sign-in, and the gateway stores them in its `users` table.
- The admin is one account: the gateway compares the object id in the token with a configured value.
- The user flow and the tenant settings were made by hand. They are not in Terraform. The app registrations are in Terraform.

## Consequences

- Sign-up needs no password and no social account. Entra ID sends the code and proves the email address.
- **Visitors are guests in the tenant that also holds the subscription.** The sign-up gives them no Azure role, and the guest level is the most restricted one. It is still a weaker separation than a tenant of their own, and it is a known gap.
- A person who asks for deletion has data in two places: the gateway tables and the guest user object in the tenant.
- The tenant settings are global. They change guest behaviour for anything else in this tenant.
- Verified live on 2026-11-17: a full sign-up with a test mailbox ("Create one", then "Sign up with email", the code mail in less than 5 s, the name, back on the site), then a silent sign-in on the cluster host name.
- The Microsoft pages and the code mail show the tenant's display name and domain, not the product. These are tenant settings, and they are a known gap.

## What a bank would add

- **A separate customer identity tenant** (or another customer identity product), with no link to the infrastructure tenant.
- **Stronger proof of identity** at sign-up, and multi-factor sign-in.
- **The identity configuration as code**, reviewed like any other change.
- **A legal review** of the consent text and of the data that the sign-up collects.
