# ADR-0028 — Track PostgreSQL simulation replica ownership

Date: 2026-11-25

## Decision

Use a PostgreSQL session lock to identify the API replica that owns each active simulation.

## Context

API replicas share simulation metadata and tenant quotas in PostgreSQL. Each replica runs its jobs in its own process. A pending or running job spends quota until it reaches a terminal state. Recovery needs evidence that PostgreSQL released the job's owner session.

## Options

| Option | Pros | Cons | Effort |
| --- | --- | --- | --- |
| Hold a PostgreSQL session lock | PostgreSQL releases the lock at session end | Uses one connection per replica | Medium |
| Renew a time-based lease | Uses brief database connections | A delayed renewal can expire a live replica's jobs | Medium |
| Use job age | Uses the submission timestamp | Long jobs can exceed the age threshold | Low |

## Rationale

Each replica takes a unique lock before it admits jobs. Job admission, quota lookup, and job lookup check the owner's lock. If the lock is absent, one transaction marks the active job failed and writes its audit entry. A terminal job cannot accept a later state update. [PostgreSQL advisory locks](https://www.postgresql.org/docs/16/explicit-locking.html#ADVISORY-LOCKS)

## Consequences

A replica can recover quota as soon as PostgreSQL detects the session loss. The operator must restart a replica that loses its ownership session. See the [deployment requirements](../architecture.md#tenant-isolation) for the initial migration.

## Status

Accepted on 2026-11-25.
