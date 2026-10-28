## ADDED Requirements

### Requirement: A use never refuses or undoes a concurrent revocation
Recording that a PAT was used SHALL NOT change any column other than its last-used time, last-used source and use
count. A revocation or rotation that overlaps a use SHALL succeed, and its effect SHALL persist.

#### Scenario: A token is revoked while its last use is being recorded
- **WHEN** a token is exchanged and, before the asynchronous last-use write completes, an operator revokes it
- **THEN** the revoke succeeds, the token remains revoked after the write completes, and the next exchange
  that is not answered from a cache is refused with `401`

#### Scenario: A last-use write for a token that was rotated
- **WHEN** the last-use write runs after the token was rotated
- **THEN** the rotation's new state is unchanged and only the last-use columns differ
