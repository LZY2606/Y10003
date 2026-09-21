package com.jayway.jsonpath.internal.path;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.internal.CharacterIndex;
import com.jayway.jsonpath.internal.Path;
import com.jayway.jsonpath.internal.function.ParamType;
import com.jayway.jsonpath.internal.function.Parameter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathCompilerGuardTest {

    private static final int DEPTH = 20000;
    private static final int FUNCTION_DEPTH = 2048;

    private static String pathWithSegments(String segment, int count) {
        StringBuilder sb = new StringBuilder("$");
        for (int i = 0; i < count; i++) {
            sb.append(segment);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Depth thresholds: compilation must be stack safe on a default thread
    // ------------------------------------------------------------------

    @Test
    public void compiles_20000_bracket_property_segments() {
        Path path = PathCompiler.compile(pathWithSegments("['a']", DEPTH));
        assertNotNull(path);
        assertEquals(DEPTH + 1, ((CompiledPath) path).getRoot().getTokenCount());
    }

    @Test
    public void compiles_20000_dot_property_segments() {
        Path path = PathCompiler.compile(pathWithSegments(".a", DEPTH));
        assertNotNull(path);
        assertEquals(DEPTH + 1, ((CompiledPath) path).getRoot().getTokenCount());
    }

    @Test
    public void compiles_20000_array_index_segments() {
        Path path = PathCompiler.compile(pathWithSegments("[0]", DEPTH));
        assertNotNull(path);
        assertEquals(DEPTH + 1, ((CompiledPath) path).getRoot().getTokenCount());
    }

    @Test
    public void compiles_2048_nested_function_path_parameters() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FUNCTION_DEPTH; i++) {
            sb.append("$.sum(");
        }
        sb.append("$.numbers[0]");
        for (int i = 0; i < FUNCTION_DEPTH; i++) {
            sb.append(")");
        }

        Path current = PathCompiler.compile(sb.toString());
        assertNotNull(current);

        for (int level = 0; level < FUNCTION_DEPTH; level++) {
            PathToken root = ((CompiledPath) current).getRoot();
            assertFalse(root.isLeaf(), "level " + level + " must have a function token");
            PathToken functionToken = root.next();
            assertInstanceOf(FunctionPathToken.class, functionToken, "level " + level);
            List<Parameter> parameters = ((FunctionPathToken) functionToken).getParameters();
            assertEquals(1, parameters.size(), "level " + level + " must keep a single parameter");
            assertEquals(ParamType.PATH, parameters.get(0).getType(), "level " + level);
            current = parameters.get(0).getPath();
        }
        assertEquals("$['numbers'][0]", current.toString());
    }

    // ------------------------------------------------------------------
    // classifyBracket: single-pass dispatch of '[' tokens
    // ------------------------------------------------------------------

    private static void assertClassification(String input, BracketToken expected) {
        CharacterIndex path = new CharacterIndex(input);
        int positionBefore = path.position();
        assertEquals(expected, PathCompiler.classifyBracket(path), input);
        assertEquals(positionBefore, path.position(), "classifyBracket must not move the position: " + input);
    }

    @Test
    public void classifies_bracket_property_tokens() {
        assertClassification("['a']", BracketToken.PROPERTY);
        assertClassification("['a','b']", BracketToken.PROPERTY);
    }

    @Test
    public void classifies_bracket_array_tokens() {
        assertClassification("[0]", BracketToken.ARRAY);
        assertClassification("[1,2]", BracketToken.ARRAY);
        assertClassification("[1:2]", BracketToken.ARRAY);
        assertClassification("[-1:]", BracketToken.ARRAY);
    }

    @Test
    public void classifies_bracket_wildcard_tokens() {
        assertClassification("[*]", BracketToken.WILDCARD);
        assertClassification("[ * ]", BracketToken.WILDCARD);
    }

    @Test
    public void classifies_bracket_filter_token() {
        assertClassification("[?(@.a=='x')]", BracketToken.FILTER);
    }

    @Test
    public void classifies_bracket_placeholder_tokens() {
        assertClassification("[?]", BracketToken.PLACEHOLDER);
        assertClassification("[?,?]", BracketToken.PLACEHOLDER);
    }

    @Test
    public void classifies_unknown_bracket_tokens() {
        assertClassification("[a]", BracketToken.UNKNOWN);
        assertClassification("[ ]", BracketToken.UNKNOWN);
    }

    // ------------------------------------------------------------------
    // classifyBracket: read count depends only on the classification prefix
    // ------------------------------------------------------------------

    private static final class CountingCharSequence implements CharSequence {
        private final String delegate;
        private int reads;

        private CountingCharSequence(String delegate) {
            this.delegate = delegate;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            reads++;
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate;
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static void assertReadCountBound(String shortInput, String longInput, int skippedSpaces,
                                             BracketToken expected) {
        for (String input : new String[]{shortInput, longInput}) {
            CountingCharSequence counting = new CountingCharSequence(input);
            CharacterIndex path = new CharacterIndex(counting);
            int positionBefore = path.position();
            assertEquals(expected, PathCompiler.classifyBracket(path));
            assertEquals(positionBefore, path.position(), "classifyBracket must not move the position");
            assertTrue(counting.reads <= skippedSpaces + 8,
                    "classification of " + expected + " read " + counting.reads
                            + " chars, limit is " + (skippedSpaces + 8));
        }
    }

    @Test
    public void classify_bracket_reads_only_the_classification_prefix() {
        String suffix = repeat('x', 100000);

        assertReadCountBound("[ 'a']", "[ '" + suffix + "']", 1, BracketToken.PROPERTY);
        assertReadCountBound("[ 0]", "[ 0" + repeat('1', 100000) + "]", 1, BracketToken.ARRAY);
        assertReadCountBound("[ ?(@.a=='x')]", "[ ?(" + suffix + ")]", 1, BracketToken.FILTER);
    }

    // ------------------------------------------------------------------
    // Compatibility: behavior pinned on the pre-refactor implementation
    // ------------------------------------------------------------------

    @Test
    public void trailing_single_char_after_bracket_property_is_dropped() {
        assertEquals("$['a']", PathCompiler.compile("$['a']x").toString());
    }

    @Test
    public void trailing_multi_char_after_bracket_property_is_property() {
        assertEquals("$['a']['xy']", PathCompiler.compile("$['a']xy").toString());
    }

    @Test
    public void trailing_single_char_after_array_index_is_dropped() {
        assertEquals("$[0]", PathCompiler.compile("$[0]x").toString());
    }

    @Test
    public void trailing_multi_char_after_array_index_is_property() {
        assertEquals("$[0]['xy']", PathCompiler.compile("$[0]xy").toString());
    }

    @Test
    public void trailing_single_char_after_mixed_path_is_dropped() {
        assertEquals("$['a']['b']", PathCompiler.compile("$.a['b']c").toString());
    }

    @Test
    public void blank_padded_bracket_property_is_normalized() {
        assertEquals("$['a']", PathCompiler.compile("$[ 'a' ]").toString());
    }

    @Test
    public void bare_word_in_brackets_is_rejected() {
        InvalidPathException e = assertThrows(InvalidPathException.class,
                () -> PathCompiler.compile("$[a]"));
        assertEquals("Could not parse token starting at position 1. Expected ?, ', 0-9, * ", e.getMessage());
    }

    @Test
    public void tab_before_bracket_property_is_rejected() {
        InvalidPathException e = assertThrows(InvalidPathException.class,
                () -> PathCompiler.compile("$[\t'a']"));
        assertEquals("Could not parse token starting at position 1. Expected ?, ', 0-9, * ", e.getMessage());
    }
}
