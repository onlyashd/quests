# 0009. Use a versioned label for quest key derivation

- **Status:** Accepted
- **Date:** 2026-11-09
- **Specs:** [protocol §3.2](../specs/protocol.md#32-derived-keys)

## Context

Each participant derives a separate key per quest from their root seed, so quests can't be
linked. The source spec derives it with the HKDF label `"quest:" ‖ QuestID`. Every other label we
use carries a protocol version, and unversioned labels make a future change to the derivation
indistinguishable from the current one.

## Decision

Quest keys are derived with the label `"AQE-v1 quest:" ‖ QuestID`. This is a deliberate deviation
from the source spec. The label is **frozen at the first release**.

## Consequences

- Every label is versioned, so a future v2 derivation can coexist with v1.
- We aren't byte-compatible with any other implementation of the source spec.
- Changing the label after release would orphan every existing claim, because participants could
  no longer re-derive their quest keys. Any change needs a new ADR and a migration path.
