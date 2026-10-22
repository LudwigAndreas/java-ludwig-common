# grpc-message-mapping Specification

## Purpose
How a service converts between generated protobuf messages and its own model: with a declared
mapper, as at every other model boundary of a service, rather than with conversion code written by
hand in each gRPC adapter.
## Requirements
### Requirement: A message is mapped to and from a model by a declared mapper
A service SHALL be able to declare a mapper between a generated protobuf message and a model type as
an interface, with no hand-written conversion, and the generated mapping SHALL respect protobuf's
presence and collection semantics.

#### Scenario: A model with an absent optional value is mapped to a message
- **WHEN** a model whose optional field is `null` is mapped to a message
- **THEN** the message field is left unset and no exception is thrown

#### Scenario: A message with an unset optional field is mapped to a model
- **WHEN** a message whose optional field is unset is mapped to a model
- **THEN** the model field is `null`, not the protobuf default value

#### Scenario: Repeated fields are mapped
- **WHEN** a model with a list of three elements is mapped to a message and back
- **THEN** the message's repeated field has the same three elements in the same order, and so does
  the model it maps back to

### Requirement: Enabling message mapping changes no existing mapper
Making protobuf-aware mapping available to services SHALL NOT change the code generated for any
mapper that involves no protobuf type.

#### Scenario: Existing mappers are regenerated
- **WHEN** both services are built before and after protobuf-aware mapping is enabled
- **THEN** every generated mapper implementation that involves no protobuf type is identical

### Requirement: The reference service serves a call through a mapped contract
`crud-service-example` SHALL publish a contract module and serve a product lookup over gRPC whose
handler converts with a declared mapper and delegates to the existing application service.

#### Scenario: An authenticated caller looks up a product
- **WHEN** a caller with a valid token asks for an existing product over gRPC
- **THEN** it receives the product with the same values the REST endpoint returns for it

#### Scenario: A caller without a catalog role looks up a product
- **WHEN** a caller with a valid token and none of the catalog roles the REST endpoint requires asks
  for a product over gRPC
- **THEN** the call fails with `PERMISSION_DENIED`, exactly as the REST endpoint answers 403, because
  the role check guards the application service both transports call

#### Scenario: A product does not exist
- **WHEN** a caller asks for a product that does not exist
- **THEN** the call fails with `NOT_FOUND` and the product-not-found problem code in its details

