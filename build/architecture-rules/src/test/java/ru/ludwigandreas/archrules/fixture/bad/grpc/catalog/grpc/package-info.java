/**
 * A module that speaks gRPC without the platform's starters: it builds its own channel and its own
 * server, binds a service nobody stereotyped, puts authorization and a transaction on a gRPC method
 * where neither is applied, and reads a repository from the adapter.
 */
package ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.grpc;
