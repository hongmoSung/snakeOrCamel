package io.snakeorcamel.caseconvert;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConvertUtilTest {

    @Nested
    class ToCamelCase {

        @ParameterizedTest
        @CsvSource({
                "my_var_name,        myVarName",
                "user_id,            userId",
                "single,             single",
                "a_b_c,              aBC",
                "ALREADY_UPPER,      alreadyUpper"
        })
        void converts_snake_case_to_camel_case(String input, String expected) {
            assertEquals(expected, ConvertUtil.toCamelCase(input));
        }

        @ParameterizedTest
        @CsvSource({
                "my-var-name,        myVarName",
                "x-y-z,              xYZ",
                "foo-bar,            fooBar"
        })
        void converts_kebab_case_to_camel_case(String input, String expected) {
            assertEquals(expected, ConvertUtil.toCamelCase(input));
        }

        @ParameterizedTest
        @CsvSource({
                "my_var-name,        myVarName",
                "mix_of-both,        mixOfBoth"
        })
        void converts_mixed_separators(String input, String expected) {
            assertEquals(expected, ConvertUtil.toCamelCase(input));
        }

        @ParameterizedTest
        @CsvSource({
                "my__var,            myVar",
                "foo_,               foo",
                "_foo,               foo"
        })
        void handles_edge_separators(String input, String expected) {
            assertEquals(expected, ConvertUtil.toCamelCase(input));
        }

        @ParameterizedTest
        @ValueSource(strings = {"_", "-", "__--__"})
        void returns_empty_for_separator_only_input(String input) {
            assertEquals("", ConvertUtil.toCamelCase(input));
        }

        @ParameterizedTest
        @CsvSource({
                "__USER--NAME__,     userName",
                "version_2_name,     version2Name",
                "café_über,          caféÜber",
                "사용자_이름,         사용자이름"
        })
        void handles_repeated_separators_digits_and_unicode(String input, String expected) {
            assertEquals(expected, ConvertUtil.toCamelCase(input));
        }

        @ParameterizedTest
        @NullAndEmptySource
        void returns_input_when_null_or_empty(String input) {
            if (input == null) {
                assertNull(ConvertUtil.toCamelCase(null));
            } else {
                assertEquals("", ConvertUtil.toCamelCase(input));
            }
        }
    }

    @Nested
    class ToSnakeCase {

        @ParameterizedTest
        @CsvSource({
                "myVarName,          my_var_name",
                "userId,             user_id",
                "MyClass,            my_class",
                "single,             single"
        })
        void converts_camel_case_to_snake_case(String input, String expected) {
            assertEquals(expected, ConvertUtil.toSnakeCase(input));
        }

        @ParameterizedTest
        @CsvSource({
                "HTTPServer,             http_server",
                "parseHTTPResponse,      parse_http_response",
                "HTTP,                   http",
                "XMLParser,              xml_parser",
                "loadJSONFromURL,        load_json_from_url"
        })
        void handles_consecutive_uppercase(String input, String expected) {
            assertEquals(expected, ConvertUtil.toSnakeCase(input));
        }

        @ParameterizedTest
        @CsvSource({
                "item1Name,          item1_name",
                "user2FA,            user2_fa",
                "version2Beta,       version2_beta"
        })
        void handles_digits(String input, String expected) {
            assertEquals(expected, ConvertUtil.toSnakeCase(input));
        }

        @ParameterizedTest
        @CsvSource({
                "getURL2Value,       get_url2_value",
                "a,                  a",
                "A,                  a",
                "caféÜber,           café_über",
                "사용자이름,          사용자이름"
        })
        void handles_short_names_acronyms_with_digits_and_unicode(String input, String expected) {
            assertEquals(expected, ConvertUtil.toSnakeCase(input));
        }

        @ParameterizedTest
        @CsvSource({
                "my-var-name,        my_var_name",
                "foo-bar,            foo_bar"
        })
        void converts_kebab_input_to_snake_case(String input, String expected) {
            assertEquals(expected, ConvertUtil.toSnakeCase(input));
        }

        @ParameterizedTest
        @ValueSource(strings = {"my_var_name", "user_id", "already_snake_case"})
        void is_idempotent_on_snake_case_input(String input) {
            assertEquals(input, ConvertUtil.toSnakeCase(input));
        }

        @ParameterizedTest
        @NullAndEmptySource
        void returns_input_when_null_or_empty(String input) {
            if (input == null) {
                assertNull(ConvertUtil.toSnakeCase(null));
            } else {
                assertEquals("", ConvertUtil.toSnakeCase(input));
            }
        }
    }
}
