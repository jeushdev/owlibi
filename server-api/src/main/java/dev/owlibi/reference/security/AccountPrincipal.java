package dev.owlibi.reference.security;
import java.io.Serializable;
import java.util.UUID;
/** Safe session identity; never retain a password/hash in a session. */
public record AccountPrincipal(UUID id, String displayName) implements Serializable {}
