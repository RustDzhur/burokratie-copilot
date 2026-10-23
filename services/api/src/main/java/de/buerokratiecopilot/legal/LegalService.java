package de.buerokratiecopilot.legal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.billing.PlatformSettings;

/**
 * Who runs the platform, which version of the legal texts is current, and the proof of who agreed to which version.
 *
 * <p>The operator's data (the imprint) is entered by the platform administrator and never invented here: a page that shows
 * a made-up address is worse than one that says nothing is entered. {@link #missing()} names what is still missing, so the
 * start-up check and the admin area can say it.</p>
 */
@Service
public class LegalService {

    /** The version of every legal text; raised when a text changes, so that the proof says which text was agreed to. */
    public static final String TEXT_VERSION = "2026-10-15";

    /** What the imprint needs before the platform is offered to the public (§ 5 DDG). */
    static final List<String> REQUIRED = List.of("name", "address", "email");

    private final PlatformSettings settings;
    private final JdbcTemplate jdbc;

    public LegalService(PlatformSettings settings, JdbcTemplate jdbc) {
        this.settings = settings;
        this.jdbc = jdbc;
    }

    /** The operator's data as entered; empty values are left out. */
    public Map<String, Object> operator() {
        Map<String, Object> stored = settings.object("legal.operator");
        Map<String, Object> clean = new LinkedHashMap<>();
        stored.forEach((key, value) -> {
            if (value != null && !String.valueOf(value).isBlank()) {
                clean.put(key, String.valueOf(value).strip());
            }
        });
        return clean;
    }

    /** The required fields that are not entered yet, and whether a second way to get in touch (phone) is missing. */
    public List<String> missing() {
        Map<String, Object> operator = operator();
        List<String> missing = new java.util.ArrayList<>();
        for (String key : REQUIRED) {
            if (!operator.containsKey(key)) {
                missing.add(key);
            }
        }
        if (!operator.containsKey("phone") && !operator.containsKey("contactForm")) {
            // § 5 DDG: a second means of fast, direct contact besides the e-mail address.
            missing.add("phone");
        }
        if (!operator.containsKey("disputeResolution")) {
            missing.add("disputeResolution");
        }
        return missing;
    }

    public Map<String, Object> publicView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("operator", operator());
        view.put("missing", missing());
        view.put("version", TEXT_VERSION);
        view.put("trialDays", settings.trialDays());
        view.put("retentionDays", settings.retentionDays());
        return view;
    }

    /**
     * Records that the person accepted the current version of the texts. The cabinet is a Kanzlei, a business, so the
     * data-processing agreement (AVV) is part of every registration.
     */
    @Transactional
    public void recordAcceptance(UUID userId) {
        record(userId, "AGB");
        record(userId, "DATENSCHUTZ");
        record(userId, "AVV");
    }

    private void record(UUID userId, String document) {
        jdbc.update("insert into legal_consents (user_id, document, version) values (?, ?, ?)", userId, document, TEXT_VERSION);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> consentsOf(UUID userId) {
        return jdbc.queryForList("select document, version, accepted_at from legal_consents where user_id = ? "
                + "order by accepted_at desc", userId);
    }
}
