# Execution Lifecycle

**Status: SUPERSEDED DESIGN NOTE.** Runtime behaviour is implemented for the synthetic execution lifecycle — see [ADR-024](../adr/024-synthetic-execution-lifecycle.md). What executes is a platform-owned workload; no tenant content enters a sandbox.

The canonical lifecycle and outcome semantics are now defined in:

- [Run semantics](run-semantics.md)
- [Run state machine](run-state-machine.md)
- [Execution protocol](execution-protocol.md)

This compatibility pointer intentionally avoids maintaining a second state diagram.
