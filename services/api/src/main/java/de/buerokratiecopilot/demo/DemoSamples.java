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

    public record Sample(String id, String filename, String text, List<String> personas) {
    }

    private static final Pattern DATE = Pattern.compile("\\{\\{([+-]\\d+)}}");
    private static final DateTimeFormatter GERMAN = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final List<Sample> all;

    public DemoSamples(ObjectMapper json) throws IOException {
        try (InputStream in = new ClassPathResource("demo/samples.json").getInputStream()) {
            this.all = List.of(json.readValue(in, Sample[].class));
        }
    }

    public List<Sample> forPersona(Persona persona) {
        return all.stream().filter(sample -> sample.personas().contains(persona.name())).toList();
    }

    public Sample byId(String id) {
        return all.stream().filter(sample -> sample.id().equals(id)).findFirst()
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
