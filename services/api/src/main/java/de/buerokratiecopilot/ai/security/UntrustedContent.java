package de.buerokratiecopilot.ai.security;

import java.util.List;
import java.util.Locale;

/**
 * Text that came from outside the platform (spec §25, E-006).
 *
 * <p>Everything a model reads from a case file — a contract, an invoice, a letter, an email body — is
 * written by somebody who is not the platform and does not have its interests. Some of it may be written
 * specifically to give the model instructions.</p>
 *
 * <p>Two things are done about that here, and neither is "ask the model nicely".</p>
 *
 * <p><strong>The content is marked as content.</strong> It is wrapped in delimiters that the instruction
 * template names, so the boundary between what the platform says and what the document says is explicit
 * in the prompt rather than implied.</p>
 *
 * <p><strong>The marking is not trusted to work.</strong> A prompt is not a security boundary; it is a
 * request. The boundary is that a tool call triggered by untrusted content is refused whatever the model
 * believes it was told — which is what {@link ToolPolicy} exists for. Treating the wrapper as the defence
 * would be the mistake this class is written to avoid.</p>
 */
public record UntrustedContent(String text, Origin origin, String reference) {

    /** Where the text came from; used to decide what may be done with it. */
    public enum Origin {
        /** A document the tenant uploaded or received. */
        DOCUMENT,
        /** An email body or subject. */
        EMAIL,
        /** A file name, which is content too and is often forgotten. */
        FILENAME,
        /** A value the platform computed itself; not untrusted, but marked for uniformity. */
        PLATFORM
    }

    /** The delimiter the instruction template uses, so the model is told where the content begins. */
    public static final String OPEN = "<<<UNTRUSTED_CONTENT";

    public static final String CLOSE = "UNTRUSTED_CONTENT>>>";

    /** Whether a model may be given any instruction-carrying decision based on this text. */
    public boolean isUntrusted() {
        return origin != Origin.PLATFORM;
    }

    /**
     * Renders the content for a prompt.
     *
     * <p>Any occurrence of the delimiter inside the content is neutralised: a document that contains the
     * closing marker could otherwise end the block early and continue as if the platform were speaking.
     * That is the simplest injection there is, and it is the one a wrapper must handle.</p>
     */
    public String forPrompt() {
        // The marker names must not appear in the replacement: writing them back would leave the
        // document able to close the block after all, which is the attack this is meant to stop.
        String safe = text == null ? "" : text
                .replace(OPEN, "[entfernt: Inhaltsmarke]")
                .replace(CLOSE, "[entfernt: Inhaltsmarke]");
        return OPEN + " source=\"" + origin.name().toLowerCase(Locale.ROOT) + "\""
                + (reference == null ? "" : " reference=\"" + reference.replace("\"", "") + "\"")
                + " >>>\n" + safe + "\n" + CLOSE;
    }

    /** A short description for the call journal, without the content. */
    public String describe() {
        return origin.name() + (reference == null ? "" : ":" + reference)
                + " (" + (text == null ? 0 : text.length()) + " Zeichen)";
    }

    /** All the untrusted parts of a prompt, in the order they would be given. */
    public static String renderAll(List<UntrustedContent> contents) {
        StringBuilder builder = new StringBuilder();
        for (UntrustedContent content : contents) {
            builder.append(content.forPrompt()).append('\n');
        }
        return builder.toString();
    }
}
