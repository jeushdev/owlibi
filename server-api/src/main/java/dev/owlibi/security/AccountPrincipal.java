package dev.owlibi.security;

import java.io.Serializable;
import java.util.UUID;

/** The identity kept in a session: only the account id and display name, never a password or hash. */
public record AccountPrincipal(UUID id, String displayName) implements Serializable {}
