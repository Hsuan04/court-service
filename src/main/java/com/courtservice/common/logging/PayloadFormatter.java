package com.courtservice.common.logging;

import com.courtservice.common.web.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns request and response payloads into log-safe strings: serialized with the application's
 * own {@link JsonMapper}, sensitive fields masked at any depth, and long output truncated.
 *
 * <p>Formatting never throws. A payload that cannot be serialized is replaced by a placeholder
 * and reported with a single WARN line, so logging can never break the API call it describes.
 */
@Slf4j
public class PayloadFormatter {

    static final String MASK = "***";

    private final JsonMapper jsonMapper;
    private final Set<String> maskedFields;
    private final int maxLength;

    /**
     * @param jsonMapper   the application's JSON mapper
     * @param maskedFields field names to mask, matched case-insensitively
     * @param maxLength    maximum length of the formatted output before truncation
     */
    public PayloadFormatter(JsonMapper jsonMapper, List<String> maskedFields, int maxLength) {
        this.jsonMapper = jsonMapper;
        this.maskedFields = maskedFields.stream()
                .map(field -> field.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.maxLength = maxLength;
    }

    /**
     * Formats a payload for logging. A {@link ResponseEntity} is unwrapped to its body, and a
     * {@link PageResponse} is reduced to its pagination summary instead of its content.
     *
     * @param value the payload, may be {@code null}
     * @return the masked, possibly truncated representation
     */
    public String format(Object value) {
        Object payload = value instanceof ResponseEntity<?> entity ? entity.getBody() : value;
        if (payload == null) {
            return "null";
        }
        if (payload instanceof PageResponse<?> page) {
            return summarize(page);
        }
        try {
            JsonNode tree = jsonMapper.valueToTree(payload);
            mask(tree);
            return truncate(tree.toString());
        } catch (RuntimeException exception) {
            log.warn("Could not serialize {} for request logging: {}",
                    payload.getClass().getName(), exception.getClass().getSimpleName());
            return "<unserializable " + payload.getClass().getSimpleName() + ">";
        }
    }

    private String summarize(PageResponse<?> page) {
        PageResponse.PageMeta meta = page.page();
        return "PageResponse[page=%d, size=%d, totalElements=%d, totalPages=%d, contentCount=%d]".formatted(
                meta.number(), meta.size(), meta.totalElements(), meta.totalPages(), page.content().size());
    }

    private void mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(object.propertyNames());
            for (String name : names) {
                if (maskedFields.contains(name.toLowerCase(Locale.ROOT))) {
                    object.put(name, MASK);
                } else {
                    mask(object.get(name));
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::mask);
        }
    }

    private String truncate(String text) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(truncated, original length " + text.length() + ")";
    }
}
