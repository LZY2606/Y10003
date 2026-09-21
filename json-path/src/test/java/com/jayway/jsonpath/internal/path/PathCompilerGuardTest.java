package com.jayway.jsonpath.internal.path;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.internal.CharacterIndex;
import com.jayway.jsonpath.internal.Path;
import com.jayway.jsonpath.internal.function.ParamType;
import com.jayway.jsonpath.internal.function.Parameter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    public void compiles_20000_bracket_property_segments_on_default_stack() {
        Path path = PathCompiler.compile(pathWithSegments("['a']", DEPTH));
        assertThat(path).isNotNull();
    }

    @Test
    public void compiles_20000_dot_property_segments_on_default_stack() {
        Path path = PathCompiler.compile(pathWithSegments(".a", DEPTH));
        assertThat(path).isNotNull();
    }

    @Test
    public void compiles_20000_array_index_segments_on_default_stack() {
        Path path = PathCompiler.compile(pathWithSegments("[0]", DEPTH));
        assertThat(path).isNotNull();
    }

    @Test
    public void compiles_2048_nested_function_path_parameters_on_default_stack() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FUNCTION_DEPTH; i++) {
            sb.append("$.sum(");
        }
        sb.append("$.numbers[0]");
        for (int i = 0; i < FUNCTION_DEPTH; i++) {
            sb.append(")");
        }

        Path compiled = PathCompiler.compile(sb.toString());
        assertThat(compiled).isNotNull();

        Path current = compiled;
        for (int level = 0; level < FUNCTION_DEPTH; level++) {
            PathToken first = ((CompiledPath) current).getRoot().getNext();
            assertThat(first).isInstanceOf(FunctionPathToken.class);
            FunctionPathToken function = (FunctionPathToken) first;
            assertThat(function.getFunctionName()).isEqualTo("sum");
            List<Parameter> parameters = function.getParameters();
            assertThat(parameters).hasSize(1);
            Parameter parameter = parameters.get(0);
            assertThat(parameter.getType()).isEqualTo(ParamType.PATH);
            current = parameter.getPath();
        }
        assertThat(current.toString()).isEqualTo("$['numbers'][0]");
    }

    private static BracketToken classify(String path) {
        CharacterIndex ci = new CharacterIndex(path);
        int positionBefore = ci.position();
        BracketToken result = PathCompiler.classifyBracket(ci);
        assertThat(ci.position()).isEqualTo(positionBefore);
        return result;
    }

    @Test
    public void classifyBracket_categories() {
        assertThat(classify("['a']")).isEqualTo(BracketToken.PROPERTY);
        assertThat(classify("['a','b']")).isEqualTo(BracketToken.PROPERTY);
        assertThat(classify("[0]")).isEqualTo(BracketToken.ARRAY);
        assertThat(classify("[1,2]")).isEqualTo(BracketToken.ARRAY);
        assertThat(classify("[1:2]")).isEqualTo(BracketToken.ARRAY);
        assertThat(classify("[-1:]")).isEqualTo(BracketToken.ARRAY);
        assertThat(classify("[*]")).isEqualTo(BracketToken.WILDCARD);
        assertThat(classify("[ * ]")).isEqualTo(BracketToken.WILDCARD);
        assertThat(classify("[?(@.a=='x')]")).isEqualTo(BracketToken.FILTER);
        assertThat(classify("[?]")).isEqualTo(BracketToken.PLACEHOLDER);
        assertThat(classify("[?,?]")).isEqualTo(BracketToken.PLACEHOLDER);
        assertThat(classify("[a]")).isEqualTo(BracketToken.UNKNOWN);
        assertThat(classify("[ ]")).isEqualTo(BracketToken.UNKNOWN);
    }

    @Test
    public void classifyBracket_does_not_move_position() {
        CharacterIndex ci = new CharacterIndex("$['a']");
        ci.setPosition(1);
        assertThat(PathCompiler.classifyBracket(ci)).isEqualTo(BracketToken.PROPERTY);
        assertThat(ci.position()).isEqualTo(1);
    }

    private static final class CountingCharSequence implements CharSequence {
        private final CharSequence delegate;
        private int reads;

        private CountingCharSequence(CharSequence delegate) {
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
            return delegate.toString();
        }
    }

    private static int countClassifyReads(CharSequence path) {
        CountingCharSequence counting = new CountingCharSequence(path);
        CharacterIndex ci = new CharacterIndex(counting);
        int positionBefore = ci.position();
        PathCompiler.classifyBracket(ci);
        assertThat(ci.position()).isEqualTo(positionBefore);
        return counting.reads;
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    public void classifyBracket_reads_only_the_classification_prefix() {
        String suffix = repeat('x', 100000);

        int propertyShort = countClassifyReads("['a']");
        int propertyLong = countClassifyReads("['a" + suffix + "']");
        assertThat(propertyLong).isEqualTo(propertyShort);
        assertThat(propertyLong).isLessThanOrEqualTo(0 + 8);

        int arrayShort = countClassifyReads("[0]");
        int arrayLong = countClassifyReads("[0" + repeat('0', 100000) + "]");
        assertThat(arrayLong).isEqualTo(arrayShort);
        assertThat(arrayLong).isLessThanOrEqualTo(0 + 8);

        int filterShort = countClassifyReads("[?(@.a=='x')]");
        int filterLong = countClassifyReads("[?(@.a=='" + suffix + "')]");
        assertThat(filterLong).isEqualTo(filterShort);
        assertThat(filterLong).isLessThanOrEqualTo(0 + 8);

        int spacedShort = countClassifyReads("[   'a']");
        int spacedLong = countClassifyReads("[   'a" + suffix + "']");
        assertThat(spacedLong).isEqualTo(spacedShort);
        assertThat(spacedLong).isLessThanOrEqualTo(3 + 8);
    }

    @Test
    public void trailing_characters_after_bracket_token_keep_legacy_behavior() {
        assertThat(PathCompiler.compile("$['a']x").toString()).isEqualTo("$['a']");
        assertThat(PathCompiler.compile("$['a']xy").toString()).isEqualTo("$['a']['xy']");
        assertThat(PathCompiler.compile("$[0]x").toString()).isEqualTo("$[0]");
        assertThat(PathCompiler.compile("$[0]xy").toString()).isEqualTo("$[0]['xy']");
        assertThat(PathCompiler.compile("$.a['b']c").toString()).isEqualTo("$['a']['b']");
    }

    @Test
    public void bracket_property_with_spaces_compiles() {
        assertThat(PathCompiler.compile("$[ 'a' ]").toString()).isEqualTo("$['a']");
    }

    @Test
    public void unquoted_bracket_content_fails_with_legacy_message() {
        assertThatThrownBy(() -> PathCompiler.compile("$[a]"))
                .isInstanceOf(InvalidPathException.class)
                .hasMessage("Could not parse token starting at position 1. Expected ?, ', 0-9, * ");
    }

    @Test
    public void tab_inside_bracket_fails_with_legacy_message() {
        assertThatThrownBy(() -> PathCompiler.compile("$[\t'a']"))
                .isInstanceOf(InvalidPathException.class)
                .hasMessage("Could not parse token starting at position 1. Expected ?, ', 0-9, * ");
    }
}
