package com.jayway.jsonpath.internal.path;

/**
 * Classification of a token that starts with an open square bracket '['.
 * Used by PathCompiler.classifyBracket to dispatch to a single reader
 * instead of trial-and-error parsing.
 */
enum BracketToken {
    PROPERTY,
    ARRAY,
    WILDCARD,
    FILTER,
    PLACEHOLDER,
    UNKNOWN
}
