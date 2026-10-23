package de.buerokratiecopilot.billing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The price list and the terms of the free trial, readable without signing in: the landing page and the
 * registration page show them. Nothing here is tenant data.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicPlansController {

    private final JdbcTemplate jdbc;
    private final PlatformSettings settings;

    public PublicPlansController(JdbcTemplate jdbc, PlatformSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @GetMapping("/plans")
    public Map<String, Object> plans() {
        // No session here, so the connection is in the system context already; plans and settings are global.
        List<Map<String, Object>> plans = RequestContext.runAs((java.util.UUID) null,
                () -> jdbc.queryForList("select code, name, price_cents, currency, tier, analyses_per_month, features from plans where is_active order by sort_order"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trialDays", settings.trialDays());
        result.put("retentionDays", settings.retentionDays());
        result.put("plans", plans);
        return result;
    }
}
