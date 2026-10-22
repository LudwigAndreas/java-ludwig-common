## Why

`add-grpc-starters` left three things a final 3.0.0 platform should not ship with. A gRPC contract
module is about sixty lines of POM copied from a script, two of which fail silently when wrong. Every
gRPC adapter hand-writes the conversion between generated messages and its service model, because
MapStruct does not understand protobuf builders. And the machine-token cache exists twice - once in
`rest-client-spring-boot-starter`, once in `grpc-client-spring-boot-starter` - recorded as owed
work. 3.0.0 is a major release, the one release where removing published types is permitted, so
this is when to fix all three.

## What Changes

- **New `build/ludwig-grpc-api-parent`** (POM tier: a parent, parented by the reactor root `common`,
  importing `ludwig-bom`). Every `<service>-grpc-api` module inherits it and gets the gRPC and
  protobuf dependencies, `grpc-core` (so `ludwig/operation/v1/operation.proto` can be imported), and
  the protobuf generator - so a contract module's POM is its parent, its `artifactId` and its
  description, and its only other file is the `.proto`.
- **A gate check replaces the scaffolding script.** `scripts/manifest.sh layout` fails when a
  `*-grpc-api` module does not inherit `ludwig-grpc-api-parent` or is missing from the root
  reactor, `ludwig-bom` or `build/jacoco-aggregate`. `scripts/new_grpc_api.sh` and
  `scripts/new_grpc_api.py` are deleted, with every mention of them.
- **MapStruct maps protobuf messages.** `no.entur.mapstruct.spi:protobuf-spi-impl` joins MapStruct
  on the annotation-processor path of `ludwig-service-parent`, so a mapper between a generated message
  and a `service.model` type is an interface, like every other mapper in a service.
- **A worked example.** `crud-service-example` publishes `catalog-grpc-api` and serves `GetProduct`
  over gRPC through `grpc-server-spring-boot-starter`, with a MapStruct mapper and nothing written by
  hand between the message and the model.
- **New `sources/outbound-token-core`** (library, zero in-repo dependencies): the one cached,
  single-flight, skew-refreshed machine token (`CachedToken`, `CachingTokenSource`), the one
  client-credentials minter, and the one reader of the caller's bearer token for relay. Both client
  starters depend on it.
- **BREAKING:** `ru.ludwigandreas.restclient.auth.CachedToken` and
  `ru.ludwigandreas.restclient.auth.CachingTokenSource` are removed from
  `rest-client-spring-boot-starter`, **without a prior deprecation**, by the owner's explicit
  decision for 3.0.0. The api-evolution rule requires a deprecating release before a removal; this
  change records the exception as justified `revapi.differences` entries instead of a deprecation
  cycle, and says so in the release notes. No module in this repository uses either type outside
  `rest-client`.
- **BREAKING (unreleased, so no revapi entry):** `grpc-client-spring-boot-starter`'s own
  `auth.CachedToken`, `auth.CachingTokenSource`, `auth.ClientCredentialsMinter` and
  `auth.InboundToken` are removed; they were added after v2.0.0 and have no baseline.
- The owed-work notes about the duplicate are removed from both client READMEs (both locales) and
  from `CLAUDE.md`.

## Capabilities

### New Capabilities

- `grpc-contract-module`: how a service's gRPC contract module is declared - inheriting one parent
  that supplies everything but the `.proto`, registered completely, and checked by the gate.
- `outbound-token`: the one implementation of an outbound machine token and of relaying the caller's
  token, shared by every client starter.
- `grpc-message-mapping`: mapping between generated protobuf messages and service models through
  MapStruct, as for any other model boundary in a service.

### Modified Capabilities

- `repository-layout`: an added requirement - `ludwig-grpc-api-parent` belongs in `build/` and a
  `*-grpc-api` module in `sources/`, each with its own role.
- `pom-topology`: an added requirement - a fourth published POM tier, the contract parent, and
  where gRPC contract modules sit in it.

Shared contracts in `openspec/specs/` touched: `repository-layout` and `pom-topology` (added
requirements only). The `grpc-contract`, `grpc-client` and `grpc-server` capabilities are still
deltas inside the unarchived `add-grpc-starters`; this change edits that change's
`grpc-contract` delta to drop the scaffolding-script scenario its task 8.2 added.

## Impact

| Module | Tier | Change | In-repo dependents the gate must run |
|---|---|---|---|
| `outbound-token-core` | library (new) | new | `rest-client-spring-boot-starter`, `grpc-client-spring-boot-starter` |
| `ludwig-grpc-api-parent` | parent (new) | new | every `*-grpc-api`: `catalog-grpc-api` |
| `catalog-grpc-api` | library (new) | new | `crud-service-example` |
| `rest-client-spring-boot-starter` | library (starter) | token classes removed, depends on `outbound-token-core` | `crud-service-example`, `export-spring-boot-starter`, `reconciliation-spring-boot-starter`, `test-support-local` |
| `grpc-client-spring-boot-starter` | library (starter) | token classes removed, depends on `outbound-token-core` | none |
| `crud-service-example` | service | serves `GetProduct` over gRPC | none |
| `ludwig-service-parent` | parent | protobuf SPI on the processor path | both services (`crud-service-example`, `notification-service`) |
| `ludwig-bom` | bom | three new modules, `protobuf-spi-impl` version | everything |
| `jacoco-aggregate` | aggregate | three new modules | none |
| root `pom.xml` (`common`) | reactor | three `<module>` entries, `revapi.differences` entries | everything |

Because `ludwig-bom`, `ludwig-service-parent` and the root POM change, the only gate is
`mvn clean install`. `scripts/project_index.py` gains the role and the check; `scripts/gate.sh`
already runs `manifest.sh layout`. The version is a tag: 3.0.0 is cut by tagging `master`, and
`.mvn/maven.config` is not edited.

## Non-goals

- Generating `.proto` files from Java types, or gRPC with a non-protobuf marshaller. Proto-first
  stays; the wire contract, its field numbers and `idempotency_level` are written where they are
  decided.
- Wire-level compatibility checking of `.proto` changes (`buf breaking` or a descriptor baseline) -
  still the follow-up `add-grpc-starters` names.
- Moving `rest-client`'s authentication providers or SPI into the token library. Only what both
  starters genuinely share moves; each starter keeps how it attaches a token to its own transport.
- Adopting gRPC in `notification-service`, or a gRPC client in `crud-service-example`.
- Fixing the flaky `PatDirectAuthenticationIT` seen during `add-grpc-starters`.
