# 0001. Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-11-09
- **Specs:** all

## Context

FERA Quests makes many decisions whose reasons aren't visible in the code: cryptographic
choices, privacy trade-offs, what the server is allowed to know. Until now they lived in
"Decisions log" bullets at the end of each spec and in "decided 2026-11-09" notes scattered
through the text. That records *what* was decided, but not the alternatives or the cost, so a
future maintainer can't tell whether a decision still holds when circumstances change.

## Decision

We record each architecturally significant decision as an Architecture Decision Record in
`docs/adr/`, following Michael Nygard's format (context, decision, consequences) plus the options
considered. Use [`template.md`](template.md).

- Files are numbered `NNNN-short-title.md` and never renumbered.
- An accepted ADR is immutable. To change a decision, write a new ADR that supersedes it, and only
  update the old one's status line to `Superseded by NNNN`.
- The specs describe the system *as it is*. ADRs explain *why*. Specs link to the ADRs that shape
  them, and each spec's "Decisions log" lists ADRs instead of repeating them.
- Decisions inherited from the source specification (D1–D4) are recorded as ADRs too, so every
  choice is in one place.

## Consequences

- A change that contradicts an ADR needs a new ADR in the same pull request, which forces the
  trade-off to be discussed explicitly.
- Some duplication between specs and ADRs is accepted; the spec stays the normative description.
