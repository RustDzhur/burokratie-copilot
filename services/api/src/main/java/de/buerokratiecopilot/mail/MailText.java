package de.buerokratiecopilot.mail;

import java.util.regex.Pattern;

/** The readable text of a mail body. Mail comes from outside: its HTML is reduced to text and never shown as HTML. */
public final class MailText {

    private static final Pattern LOOKS_LIKE_HTML = Pattern.compile("(?i)<(html|body|div|p|br|table|span|a|img|style)\\b");

    private MailText() {
    }

    public static boolean looksLikeHtml(String text) {
        return text != null && LOOKS_LIKE_HTML.matcher(text).find();
    }

    public static String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>|</h[1-6]>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&auml;", "ä").replace("&ouml;", "ö")
                .replace("&uuml;", "ü").replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&Uuml;", "Ü")
                .replace("&szlig;", "ß")
                .replaceAll("[ \\t\\u00a0]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /** The text to show: the plain part, or the text of the HTML part when there is no plain one. */
    public static String readable(String plain, String html) {
        if (plain != null && !plain.isBlank()) {
            return looksLikeHtml(plain) ? htmlToText(plain) : plain.strip();
        }
        return htmlToText(html);
    }
}
