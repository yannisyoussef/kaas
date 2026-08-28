# ADR-021: Consume dispatches durably, and make ownership of a run recoverable

- **Status:** IMPLEMENTED
- **Date:** 2026-08-28
- **Decision owners:** KaaS control-plane architecture
- **Scope:** A production RabbitMQ consumer, a durable consumer inbox, `QUEUED → CLAIMED`, the assignment epoch as a fencing token, a server-controlled worker lease with heartbeats, lease-expiry recovery, and cancellation of owned work. No provisioning, no execution command, no source or secret capability, no execution.

## Context

The relay slice put dispatch intents on a broker that nothing consumed. The queue was, by design, a place where correct messages accumulated. This slice makes them arrive somewhere.

That turns a message into something dangerous for the first time. Until now every input to the lifecycle came through an authenticated tenant request; now the control plane reads bytes off a broker and has to decide what they are allowed to cause. And claiming introduces the first state a run can be stuck in that somebody *owns* — which, without a way to take ownership back, would recreate the availability defect the early-terminal slice existed to remove, one state later.

## Decision

### A broker message is transport, never authority

The consumer treats every delivery as untrusted input. It re-derives the semantic digest from the delivered bytes, then looks the message identity up in `execution_dispatches` and compares every field — tenancy, project, run, version, attempt, snapshot identity and digest — against the row the control plane wrote when it produced the message. Only then does it read the run, under a lock, and only then does it compare-and-set.

The message contributes an identity to look things up by. It cannot decide, extend, or override anything else. Cross-tenant and cross-project substitution die at the corroboration step: a payload naming another organization's run cannot match the dispatch its own message identity resolves to.

**Headers are not authority either.** The message type and schema version in the transport headers can only ever cause a *rejection* — the same values are checked against the body, and the digest covers the body alone. A header that disagrees with the body is a reason to disbelieve the message, not a reason to prefer the header.

**Unknown properties are a hard failure.** Jackson defaults `FAIL_ON_UNKNOWN_PROPERTIES` to false, which would let an attacker append fields the digest does not cover and that a future, more trusting reader might act on. The relay slice already shipped a defect of the opposite shape — verifying a reduced projection while publishing different raw bytes — so this is a known way to be wrong here.

### The database commits before the broker is acknowledged

This is the ordering the whole design rests on. Acknowledging first means a process death between the two loses the work while the broker believes it was handled. Committing first means the same death causes a redelivery, which the inbox absorbs.

So: delivery → transaction → commit → acknowledge. The listener returns only after the transaction has committed, and the container acknowledges only when the listener returns.

### The inbox is keyed by message identity, and redelivery is a counter

Delivery tags are channel-local transport metadata that change on every redelivery and mean nothing across connections; the broker's `redelivered` flag is a hint. Neither is deduplication truth. The inbox is keyed by `(consumer, messageId)`.

`DUPLICATE` is deliberately **not** a disposition. A message's fate does not change when the broker offers it again — only the number of times it has been offered — so a redelivery increments `delivery_count` on the decision that already exists. Recording a second, different decision for the same message would mean the inbox held two answers to one question.

**Same identity, different bytes is a conflict, not a duplicate.** It is two different messages claiming to be one. The recorded decision is not overwritten to accommodate the newcomer, and the event is logged at error level.

### Requeue is for our failures; rejection is for the message's

A plain exception means the control plane failed and nothing was decided: the delivery is requeued, because the work is real and losing it would be worse than retrying. A durable decision that the message can never succeed — malformed, unsupported, digest mismatch, identity conflict — is rejected without requeue and dead-lettered.

Requeue is paced. Without a pause, a database outage makes every delivery fail instantly, requeue instantly, and redeliver instantly, spinning the consumer, the broker, and the log for the length of the outage. Dead-lettering instead would be worse: nothing is wrong with the message, and discarding real work to protect a loop is the wrong trade.

**Stale messages are acknowledged, never dead-lettered.** A dispatch that arrives for a run cancelled or timed out while it was in flight is expected distributed-system behaviour. Routing it to an operator queue would bury the real failures under events that need no operator.

### Consumer dead-lettering is a different problem from publisher failure

A publisher terminal disposition means the control plane could not get a message onto the broker; it lives in `outbox_messages` and never reaches RabbitMQ. A consumer dead letter means a message arrived and could not be believed. They share nothing but a name, and they get separate destinations.

**This changes the dispatch queue's declaration.** RabbitMQ refuses to redeclare a queue with different arguments and fails the channel with `PRECONDITION_FAILED`, so adding a dead-letter exchange is not a transparent addition: an environment that already declared the queue without one must delete and recreate it. No deployment exists yet, which is the only reason this is safe to do now rather than under a new queue name.

### The assignment epoch is a fencing token

Claiming binds one worker instance to the attempt under epoch 1. Every operation that matters checks identity *and* epoch: an epoch alone would let any worker pose as the current owner, and an identity alone would let a restarted worker act under an assignment it has already lost.

The epoch survives fencing rather than being cleared, because a later assignment has to be strictly greater than it. Reassignment of the same attempt would raise the epoch; an infrastructure retry would create a new attempt entirely. The two are different things and the schema keeps them apart.

The worker identity is **audit, not authorization**. It records which instance the control plane handed the assignment to. It is server-controlled configuration, never a value from the message, and it never decides whether that instance may do anything.

### Ownership is not permission to execute

A claim records who owns the infrastructure attempt. It issues no execution command, no source capability, and no secret capability, and it does not advance the run toward provisioning. Those are the security-sensitive decisions, and they belong to a slice that can give them the attention they need. An ArchUnit rule now fails the build if the claim path ever reaches a runner, a Karate class, an object store, a container launcher, or a secret provider.

### Owned work cannot end in one step

The canonical state machine routes cancellation and lease loss through `CLAIMED → STOPPING → COMPLETED`, and `RunLifecycle` has always forbidden `CLAIMED → COMPLETED` outright. Preserving that was not a preference; changing it would mean rewriting the state machine four slices have been built against.

So cancelling an owned run is a *request*: the assignment is fenced, the run enters `STOPPING`, and the reconciler settles it. That makes the `202` this contract has always described — "cancellation was durably requested and worker termination is pending" — reachable for the first time.

**One documented deviation.** Canonically `STOPPING → COMPLETED` waits a thirty-second stop-acknowledgement grace. In this slice there is no stop command and nothing that could acknowledge one, so waiting would be latency for an event that provably cannot arrive. The reconciler settles `STOPPING` on its next pass instead. `STOPPING` remains a real persisted state with its own version and lifecycle event; the grace becomes meaningful when a sandbox exists to receive a stop. This is the "document and review rather than collapse casually" the design called for — the state is not collapsed, only the imaginary wait is.

The stopping set is read *before* anything is fenced, so a run fenced in one pass settles in the next. Settling in the same breath would be correct but would make `STOPPING` unobservable in practice, and a state nothing can ever see is a state nobody maintains.

### A lost lease is not a timeout

`LEASE_LOST` pairs with infrastructure outcome `FAILED` and phase `CLAIM`. A queue deadline means the platform never got to the run; a lost lease means it did, and then lost the worker holding it. Those need different diagnoses, so they must not share an outcome. Neither is a cancellation: nobody asked.

Fencing waits out a recovery window after expiry, so a worker that misses one heartbeat to a garbage-collection pause or a brief partition is not punished for it.

### Why the stop reason is a column

`STOPPING` has to remember which outcome it owes. The alternative — reading "stopping with no cancellation request" as "the lease was lost" — stores a fact as the absence of another fact, and the reconciler that settles the run would be deriving the outcome from a silence. That is how the wrong outcome gets written.

### The heartbeat is a different API with a different audience

It lives under `/internal/v1` on its own security chain, and is deliberately absent from the public OpenAPI document. The two surfaces have different authentication shapes, not merely different paths: a tenant token carries an `org_id` and acts for one organization, while a platform service token carries no tenancy at all, because a worker is not a tenant and every scope it touches comes from the run it names. Mixing them into one chain would mean the tenant converter had to tolerate a missing organization — precisely the hole that lets an unscoped token reach tenant data.

Service subjects must be in the reserved `kaas.` namespace, which tenant tokens are refused for. A token carrying both a reserved subject and an organization is refused outright: a credential that is simultaneously a service and a tenant is a confusion waiting to be exploited.

A heartbeat bumps no version, emits no lifecycle event, and cannot renew an expired or fenced lease. Renewing an expired lease would let a worker take ownership back by being late rather than by being correct, and would undo the reconciler's whole basis for fencing.


### The consumer ships disabled, and that is a finding not a gap

It is implemented, proven end to end against a real broker, and safe to enable. Nothing in this repository can yet
send a heartbeat, so with the consumer on every run is claimed within a second and loses its lease sixty seconds
later, terminalizing `FAILED` / `LEASE_LOST` / `CLAIM`. That is a *false* diagnosis: it asserts the platform
reached the run and lost the worker holding it, when no worker ever existed. Before this slice the same run ended
`TIMED_OUT` / `QUEUE_DEADLINE` — "the platform never got to it" — which was true. Enabling it by default would
trade a true diagnosis for a false one, in a slice whose entire argument is that those two must not be confused.

Starting the consumer with lease reconciliation disabled is refused at startup, because the queue-deadline reaper
only selects `QUEUED` runs: consuming without reconciling leaks one admission slot per claimed run, silently.

## Alternatives considered

### Trusting the message and validating later

Rejected. Every check that runs after a state change is a check that has already failed to prevent anything.

### Deduplicating on the broker's delivery tag or `redelivered` flag

Rejected. Both are transport metadata scoped to a channel. Neither survives a reconnect, and neither is a statement about the message.

### `CLAIMED → COMPLETED` directly

Rejected, and it was never really available: the domain's own transition table forbids it. It would also mean a cancelled run reported as finished while its assignment was still live.

### Dead-lettering stale messages

Rejected. Stale deliveries are the normal consequence of at-least-once publication racing a cancellation. An operator queue full of them is an operator queue nobody reads.

### Retrying permanently-refused messages a few times before dead-lettering

Rejected as unnecessary. The inbox makes the retry idempotent, so it would be harmless — but it would also be pure latency before an outcome that cannot change, and it obscures the distinction between "we failed" and "the message is wrong".

### A separate consumer service

Rejected for now. The consumer is an inbound adapter in the same deployable, with an ArchUnit rule keeping the dependency one-way. Splitting it is a deployment decision that nothing yet forces, and doing it before there is a worker to talk to would be inventing a boundary from guesswork.

## Consequences

- A dispatch can now cause exactly one state change in the control plane, and only after the control plane has corroborated every field of it against its own records.
- `CLAIMED` counts as active admission capacity, as every non-terminal state does. That capacity is released by cancellation or by lease recovery, both of which end at `COMPLETED`.
- The dispatch queue must be recreated in any environment that already declared it, because its arguments changed.
- The inbox is never pruned. Rows must outlive the broker's maximum plausible redelivery window, because deleting one turns the next redelivery back into an undecided message. A retention policy is documented as required and deliberately not invented here.
- Four background timers now run: scheduling, relay, queue-deadline reaping, and lease reconciliation.
- `REQUESTED` is now a reachable cancellation status, for owned work only. Unowned work still acknowledges in the same transaction it requests.

## Constraints the worker-execution slice must still rewrite

`guard_execution_attempt` permits claim, heartbeat, and fence, and nothing else — provisioning and every later attempt state will have to extend it. `require_complete_scheduling_bundle` still demands exactly one attempt per run, so infrastructure retry must rewrite it together with the single-attempt uniqueness constraints. `ck_run_lifecycle_events_transition` enumerates six shapes and will need every transition the execution slice adds.

## Verification

PostgreSQL 16.10 and RabbitMQ 3.13 Testcontainers, with the production consumer enabled. Covered: a run travelling from PostgreSQL through RabbitMQ to a claim with nobody driving it; redelivery as a decided no-op that creates no second claim and no second version; the crash window between commit and acknowledgement; a known identity carrying different bytes recorded as a conflict without overwriting the original decision; malformed, oversized, unsupported-type, unsupported-version, unknown-property, digest-mismatched, and transport-identity-mismatched messages each refused under the reason that actually applies; a refused message dead-lettered while a stale one is merely acknowledged; cross-tenant, cross-project, and unknown-dispatch substitution never claimed; eight concurrent claimers producing exactly one owner, one epoch, one transition, and a captured outcome for every racer; heartbeats renewing only the assignment they can name, and refused for the wrong epoch, the wrong worker, the wrong attempt, a tenant credential, and a hybrid credential; a lease left alone while live, fenced only after its recovery window, and unable to be revived by a late heartbeat; a fenced run settling to `FAILED`/`LEASE_LOST`/`CLAIM` and releasing capacity; cancellation of owned work answering `202`, fencing immediately, and settling as `CANCELLED`; admission still counting `CLAIMED` as active until the run settles; and every unimplemented lifecycle and assignment shape rejected by the database. Migration coverage runs fresh and populated-upgrade directions, with the populated fixture proven to reach what the upgrade changes before it runs.
