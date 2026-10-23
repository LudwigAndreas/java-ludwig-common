# Documentation

| If you are… | Start with | Then |
|---|---|---|
| a developer writing a module or service | [developer-guide.md](developer-guide.md) | [runbook.md](runbook.md) (process, versions, releases), [documentation-guide.md](documentation-guide.md), the module READMEs |
| an administrator deploying or operating services | [admin-guide.md](admin-guide.md) | each module README's Configuration, Deployment, Observability and Runbook sections |
| a system analyst specifying behaviour | [analyst-guide.md](analyst-guide.md) | [api/](api/README.md) (published OpenAPI/AsyncAPI), `openspec/specs/`, [data-scope-configuration.md](data-scope-configuration.md) |
| an AI agent working in this repository | [agent-operations.md](agent-operations.md) | [agent-state.md](agent-state.md), [code-index.md](code-index.md) |

| Document | Contents |
|---|---|
| [developer-guide.md](developer-guide.md) | architecture, module kinds, how to write a library, starter, service or contract module, observability and audit checklist, enforced conventions, testing |
| [admin-guide.md](admin-guide.md) | deployment, configuration and secrets, database tables and retention, Kafka topics, observability and alerts, audit, actuator endpoints, known operational gaps, runbook index |
| [analyst-guide.md](analyst-guide.md) | capability map, API documents, errors, long-running operations, event envelope, audit trail, data model, specifications |
| [runbook.md](runbook.md) | development and release process: features, fixes, versions, IFT, API compatibility, releases, the gate |
| [documentation-guide.md](documentation-guide.md) | where documentation lives, the module README template, how to write runbook entries |
| [data-scope-configuration.md](data-scope-configuration.md) | defining data scopes and mapping them to roles |
| [api/README.md](api/README.md) | the published API documents and how they are generated |
| [harness-enforcement.md](harness-enforcement.md) | which rules the build enforces and which are only written down |
| [code-index.md](code-index.md) | navigating the repository with the code-index server |
| [agent-operations.md](agent-operations.md), [agent-state.md](agent-state.md) | operating rules and run state for AI agents |
| `../PROJECT_INDEX.md` | generated module and dependency map |
| `../CHANGELOG.md` | release history: the only place history belongs |
