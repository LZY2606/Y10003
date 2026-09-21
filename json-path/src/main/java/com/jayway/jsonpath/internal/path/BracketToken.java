package com.jayway.jsonpath.internal.path;

/**
 * Classification of a token that starts with '['. Used by {@link PathCompiler} to pick
 * the single matching reader instead of trial-and-error dispatch.
 */
enum BracketToken {
    PROPERTY,
    ARRAY,
    WILDCARD,
    FILTER,
    PLACEHOLDER,
    UNKNOWN
}
