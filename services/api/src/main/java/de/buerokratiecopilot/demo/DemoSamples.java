package de.buerokratiecopilot.demo;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** The invented letters the demo cabinets are filled with; dates are relative to the day the demo starts. */
@Component
public class DemoSamples {

    /**
     * One invented letter.
     *
     * @param sender      the sender as the envelope says it; the demo passes it to the upload, so that the
     *                    sender is not "read" from the first lines and the cabinet does not fill with invented
     *                    supplier contacts
     * @param belegStatus where the voucher should stand in the office queue after the demo is built; null
     *                    leaves it at {@code NEU}
     */
    public record Sample(String id, String filename, String text, List<String> personas, String sender,
                         String belegStatus) {
    }

    private static final Pattern DATE = Pattern.compile("\\{\\{([+-]\\d+)}}");
    private static final DateTimeFormatter GERMAN = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final List<Sample> all;
    /** The Kanzlei demo has its own, larger set of vouchers (20 documents, three clients, some without a client). */
    private final List<Sample> kanzlei;

    public DemoSamples(ObjectMapper json) throws IOException {
        this.all = read(json, "demo/samples.json");
        this.kanzlei = read(json, "demo/kanzlei-samples.json");
    }

    private static List<Sample> read(ObjectMapper json, String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return List.of(json.readValue(in, Sample[].class));
        }
    }

    public List<Sample> forPersona(Persona persona) {
        // The Kanzlei is the product this edition sells; its demo is one office with its clients, not a
        // collection of client workspaces. The other personas keep the smaller cabinets they had.
        if (persona == Persona.ADVISOR) {
            return kanzlei;
        }
        return all.stream().filter(sample -> sample.personas().contains(persona.name())).toList();
    }

    public Sample byId(String id) {
        return java.util.stream.Stream.concat(all.stream(), kanzlei.stream())
                .filter(sample -> sample.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown demo sample " + id));
    }

    /** The text with every {{+N}} / {{-N}} replaced by the date N days from today, written the German way. */
    public static String resolve(String text, LocalDate today) {
        Matcher matcher = DATE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, today.plusDays(Integer.parseInt(matcher.group(1))).format(GERMAN));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
