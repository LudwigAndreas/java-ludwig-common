# catalog-grpc-api

***English** · [Русский](README.ru.md)*

The product catalog's gRPC contract, version 1 (`ludwig.catalog.v1.CatalogService`), served by
[`crud-service-example`](../../services/crud-service-example) beside its REST API. It is the reference contract
module: copy its shape when adding a service's gRPC API.

| | |
|---|---|
| Role | contract |
| Depends on (in-repo) | parent [`ludwig-grpc-api-parent`](../../build/ludwig-grpc-api-parent) (brings `grpc-core`) |
| Used by | `crud-service-example` (server); any caller (client stubs) |
| Surfaces | gRPC contract |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>catalog-grpc-api</artifactId>
</dependency>
```

Source: [`src/main/proto/ludwig/catalog/v1/catalog.proto`](src/main/proto/ludwig/catalog/v1/catalog.proto).
Generated Java lives in `ru.ludwigandreas.catalog.grpc.v1`.

| RPC | Request → Response | `idempotency_level` | Errors |
|---|---|---|---|
| `GetProduct` | `GetProductRequest{id}` → `Product` | `NO_SIDE_EFFECTS` | `NOT_FOUND` with the product-not-found problem code |
| `GetProducts` | `GetProductsRequest{ids[]}` → `GetProductsResponse{products[]}` (in the order asked) | `NO_SIDE_EFFECTS` | `NOT_FOUND` when any id does not exist |

`Product`: `id`, `sku`, `name`, optional `description` (absent ≠ empty), `price` as a **decimal string** (money
never travels as a float), `state` (`PRODUCT_STATE_DRAFT` / `ACTIVE` / `DISCONTINUED`), `stock_quantity`,
`category {id, code, name}`, `created_at`, `updated_at`, `version` (optimistic-lock version). The supplier cost is
in neither API.

A client calls it through [`grpc-client-spring-boot-starter`](../grpc-client-spring-boot-starter); both methods
are safe to retry because they declare `NO_SIDE_EFFECTS`.

## Configuration

None.

## Deployment

Published as a jar with every release. Breaking changes go into a new `ludwig.catalog.v2` package served beside v1,
never into an edit of v1.

## Observability

Not applicable (contract). Calls are observed by the gRPC starters on both sides.

## Audit

Not applicable.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Client fails to parse a response after a server upgrade | Incompatible change made to v1 | Revert; ship the change as v2 |
| `check_protos.sh` fails after an edit | A contract rule was broken | See [grpc-core](../grpc-core#writing-a-contract-module) |

## Testing

Generated code is excluded from coverage (`**/grpc/v*/**`). The server side is tested in `crud-service-example`
with the in-process gRPC test slice.

## Design decisions

- Money as a decimal string; optional fields where absent and empty mean different things.
- Only the fields the REST API exposes, so the two APIs disclose the same data.
