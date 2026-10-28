package de.buerokratiecopilot.shared.util;

import java.util.Collection;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.postgresql.util.PGobject;

/**
 * Rows read with plain SQL carry json and jsonb columns as {@link PGobject}, which would be written to the client as
 * {@code {"type":"jsonb","value":"..."}}. This turns them into real JSON values before the row leaves the service.
 */
public final class JsonColumns {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonColumns() {
    }

    /** Replaces the json columns of every row; returns the same rows for chaining. */
    public static <C extends Collection<Map<String, Object>>> C parse(C rows) {
        for (Map<String, Object> row : rows) {
            parse(row);
        }
        return rows;
    }

    public static Map<String, Object> parse(Map<String, Object> row) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getValue() instanceof PGobject object && ("jsonb".equals(object.getType()) || "json".equals(object.getType()))) {
                try {
                    entry.setValue(object.getValue() == null ? null : JSON.readValue(object.getValue(), Object.class));
                }
                catch (JsonProcessingException ex) {
                    throw new IllegalStateException("A json column held something that is not json", ex);
                }
            }
        }
        return row;
    }
}
