# FERA Quests documentation

Documentation for maintainers. For setting up and contributing, start with
[`CONTRIBUTING.md`](../CONTRIBUTING.md). Coding agents read [`AGENTS.md`](../AGENTS.md).

## Specifications: what the system is

The source of truth. Code follows the specs; a change to specified behaviour updates the spec in the
same pull request.

| Spec                                     | Covers                                                                             |
|------------------------------------------|------------------------------------------------------------------------------------|
| [`specs/protocol.md`](specs/protocol.md) | Cryptography and wire formats. **Normative**, implemented once in `:protocol`      |
| [`specs/backend.md`](specs/backend.md)   | Server and redeemer: schema, API, business logic, jobs, security, deployment       |
| [`specs/app.md`](specs/app.md)           | Client apps: modules, platform services, screens, security checklist, distribution |

## Decisions: why it is that way

[`adr/`](adr): Architecture Decision Records, one per significant decision, with the options
considered and the trade-offs accepted. Read them before proposing to change a fundamental choice.

## Maintainer guides: how to run the project

| Guide                                        | For                                                                           |
|----------------------------------------------|-------------------------------------------------------------------------------|
| [`architecture.md`](architecture.md)         | The big picture: components, trust boundaries, where code lives               |
| [`releasing.md`](releasing.md)               | Cutting a release: versions, tags, changelog, draft review, signing, recovery |
| [`ci.md`](ci.md)                             | Workflows, the scripts in `.cicd/`, git hooks                                 |
| [`repository-setup.md`](repository-setup.md) | GitHub settings a maintainer has to configure by hand                         |

## Conventions for these docs

- Markdown, one topic per file. Link to spec sections rather than repeating them.
- Keep guides current: a pull request that changes a workflow, script or setting updates the
  matching guide.
- Dates are absolute (`2026-11-09`), never "last week".
