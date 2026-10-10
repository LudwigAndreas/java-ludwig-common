## ADDED Requirements

### Requirement: A local mail server can be named
The local application SHALL be able to name a mail server. When named, an in-process mail server
SHALL be started on the loopback interface, `spring.mail.host` and `spring.mail.port` SHALL resolve
to it ahead of every other property source, and the start-up summary SHALL list its SMTP address
and the address and credentials a mail client uses to read what was received.

#### Scenario: A service sends mail locally
- **WHEN** a local application that named the mail server sends a message
- **THEN** the message is accepted by the local server and never leaves the machine

#### Scenario: A developer reads a received message
- **WHEN** a developer connects a mail client to the address the start-up summary lists
- **THEN** the messages the service sent are readable there

#### Scenario: The mail server is not named
- **WHEN** a local application does not name the mail server
- **THEN** no mail server is started and `spring.mail.*` is not overridden

#### Scenario: The mail library is absent
- **WHEN** a local application names the mail server and its library is not on the test classpath
- **THEN** start-up fails naming the missing dependency, before any container is started

### Requirement: Received mail is readable from a test
The messages the local mail server received SHALL be readable from code in the launched context,
so an integration test of a launched application can assert on them.

#### Scenario: A test asserts on a sent message
- **WHEN** a test launches an application that named the mail server and triggers an email
- **THEN** the test reads the message's recipient and subject from the mail server
