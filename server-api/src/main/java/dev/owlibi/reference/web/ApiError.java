package dev.owlibi.reference.web;

import java.util.Map;

/** The JSON body of every error response: a stable code, a safe message and any per-field problems. */
public record ApiError(String code, String message, Map<String, String> fieldErrors) {}
