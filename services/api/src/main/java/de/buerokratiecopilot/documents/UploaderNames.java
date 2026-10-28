package de.buerokratiecopilot.documents;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The names of the people who uploaded documents, for the list and the page of a document ("uploaded by"). */
@Component
public class UploaderNames {

    private final JdbcTemplate jdbc;

    public UploaderNames(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, String> namesOf(Collection<UUID> userIds) {
        Collection<UUID> ids = userIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.query("select id, display_name from users where id = any (?::uuid[])", rs -> {
            names.put(rs.getObject("id", UUID.class), rs.getString("display_name"));
        }, (Object) ids.toArray(UUID[]::new));
        return names;
    }
}
