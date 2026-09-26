package com.courtservice.common.logging;

import com.courtservice.common.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(OutputCaptureExtension.class)
class PayloadFormatterTest {

    private static final List<String> MASKED_FIELDS = List.of("password", "token", "secret", "authorization");
    private static final int MAX_LENGTH = 1000;

    private final PayloadFormatter formatter = new PayloadFormatter(JsonMapper.builder().build(), MASKED_FIELDS, MAX_LENGTH);

    record Credentials(String token, String label) {
    }

    record SignUp(String username, String password, Credentials credentials, List<Credentials> history) {
    }

    @Test
    void masksTopLevelNestedAndArrayFields() {
        // given
        SignUp signUp = new SignUp("alice", "p@ss", new Credentials("t-1", "main"),
                List.of(new Credentials("t-2", "old"), new Credentials("t-3", "older")));

        // when
        String formatted = formatter.format(signUp);

        // then
        assertThat(formatted)
                .contains("\"username\":\"alice\"")
                .contains("\"password\":\"***\"")
                .contains("\"credentials\":{\"token\":\"***\",\"label\":\"main\"}")
                .contains("{\"token\":\"***\",\"label\":\"old\"}")
                .contains("{\"token\":\"***\",\"label\":\"older\"}")
                .doesNotContain("p@ss", "t-1", "t-2", "t-3");
    }

    @Test
    void masksFieldNamesCaseInsensitively() {
        // when
        String formatted = formatter.format(Map.of("Authorization", "Bearer abc", "SECRET", "s"));

        // then
        assertThat(formatted).doesNotContain("Bearer abc", "\"s\"").contains("\"Authorization\":\"***\"");
    }

    @Test
    void masksNestedMapsInsideArrays() {
        // when
        String formatted = formatter.format(List.of(Map.of("inner", Map.of("password", "x"))));

        // then
        assertThat(formatted).isEqualTo("[{\"inner\":{\"password\":\"***\"}}]");
    }

    @Test
    void truncatesLongPayloadAndReportsOriginalLength() {
        // given
        PayloadFormatter shortFormatter = new PayloadFormatter(JsonMapper.builder().build(), MASKED_FIELDS, 10);
        String serialized = "{\"name\":\"" + "x".repeat(20) + "\"}";

        // when
        String formatted = shortFormatter.format(Map.of("name", "x".repeat(20)));

        // then
        assertThat(formatted).isEqualTo(serialized.substring(0, 10)
                + "...(truncated, original length " + serialized.length() + ")");
    }

    @Test
    void keepsPayloadAtExactlyMaxLength() {
        // given
        PayloadFormatter exactFormatter = new PayloadFormatter(JsonMapper.builder().build(), MASKED_FIELDS, 7);

        // when / then
        assertThat(exactFormatter.format("12345")).isEqualTo("\"12345\"");
    }

    @Test
    void summarizesPageResponseWithoutContent() {
        // given
        PageResponse<String> page = new PageResponse<>(List.of("secret-a", "secret-b"),
                new PageResponse.PageMeta(1, 2, 5, 3));

        // when
        String formatted = formatter.format(page);

        // then
        assertThat(formatted)
                .isEqualTo("PageResponse[page=1, size=2, totalElements=5, totalPages=3, contentCount=2]");
    }

    @Test
    void unwrapsResponseEntityBody() {
        // when
        String formatted = formatter.format(ResponseEntity.status(201).body(Map.of("token", "abc")));

        // then
        assertThat(formatted).isEqualTo("{\"token\":\"***\"}");
    }

    @Test
    void formatsNullAndEmptyResponseEntity() {
        assertThat(formatter.format(null)).isEqualTo("null");
        assertThat(formatter.format(ResponseEntity.noContent().build())).isEqualTo("null");
    }

    static class Exploding {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void serializationFailureDoesNotThrowAndLogsOneWarning(CapturedOutput output) {
        // when / then
        assertThatCode(() -> formatter.format(new Exploding())).doesNotThrowAnyException();
        assertThat(formatter.format(new Exploding())).isEqualTo("<unserializable Exploding>");
        assertThat(output.getOut().lines()
                .filter(line -> line.contains("Could not serialize") && line.contains("Exploding")))
                .hasSize(2)
                .allSatisfy(line -> assertThat(line).contains("WARN").doesNotContain("boom"));
    }
}
