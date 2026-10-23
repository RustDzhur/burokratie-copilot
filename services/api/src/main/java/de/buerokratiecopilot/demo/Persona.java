package de.buerokratiecopilot.demo;

import java.util.Arrays;
import java.util.Locale;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * What a visitor says they want to use the platform for; it decides the demo cabinet, its plan and what is in it.
 *
 * <p>There is one kind of cabinet now — the Kanzlei — so the demo no longer offers a private person or a household.
 * The three purposes that remain are businesses an office works for, and the office itself.</p>
 */
public enum Persona {

    /** A freelancer or sole trader. */
    FREELANCER("FREELANCER", "BUSINESS", "Freiberufler", "BUSINESS"),
    /** A larger company with employees. */
    COMPANY("COMPANY_TEAM", "PROFESSIONAL", "Unternehmen", "BUSINESS"),
    /** The office itself: an accountant looking after many clients. */
    ADVISOR("EXTERNAL_REPRESENTATIVE", "PROFESSIONAL", "Kanzlei", "ADVISOR");

    private final String accountType;
    private final String planCode;
    private final String workspaceName;
    private final String workspaceMode;

    Persona(String accountType, String planCode, String workspaceName, String workspaceMode) {
        this.accountType = accountType;
        this.planCode = planCode;
        this.workspaceName = workspaceName;
        this.workspaceMode = workspaceMode;
    }

    public String accountType() {
        return accountType;
    }

    public String planCode() {
        return planCode;
    }

    public String workspaceName() {
        return "Demo " + workspaceName;
    }

    public String workspaceMode() {
        return workspaceMode;
    }

    public static Persona parse(String value) {
        return Arrays.stream(values()).filter(p -> p.name().equalsIgnoreCase(value == null ? "" : value.strip()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Unknown demo cabinet; choose one of " + Arrays.stream(values()).map(p -> p.name().toLowerCase(Locale.ROOT)).toList()));
    }
}
