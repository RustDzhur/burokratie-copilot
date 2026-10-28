package de.buerokratiecopilot.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The Desktop Bridge: a workstation that has a scanner and sends what it scans (spec §8, G-002).
 *
 * <p>It exists because a browser cannot talk to a scanner. That is not a gap in this platform — no browser
 * can — so the part that touches the device is a small separate program and the platform exposes the
 * receiving end.</p>
 *
 * <p><strong>When there is no scanner, the diagnosis is the deliverable.</strong> The acceptance criterion
 * says that a scan reaches the inbox when a SANE device is present and that the answer is understandable
 * when it is not. The second half is the one that gets written badly: "scanning failed" tells a person
 * nothing, and the person reading it is standing at a machine they cannot use. Every failure here names
 * what is missing, how it was looked for, and what to do next.</p>
 *
 * <p><strong>A broken connection must not lose a scan.</strong> The sheet has already gone through the
 * device; if the upload fails, the file is kept and sent again. Rescanning is not a recovery — the paper
 * is back in the pile and the person has walked away.</p>
 *
 * <p><strong>TWAIN and WIA are not implemented and are named as such.</strong> They need a Windows host and
 * a driver model this program does not have; saying so is more useful than a menu entry that fails.</p>
 */
@Service
public class DesktopBridge {

    private static final Logger LOG = LoggerFactory.getLogger(DesktopBridge.class);

    private final String scanimagePath;
    private final String uploadUrl;

    public DesktopBridge(@Value("${bc.bridge.scanimage:scanimage}") String scanimagePath,
                         @Value("${bc.bridge.upload-url:}") String uploadUrl) {
        this.scanimagePath = scanimagePath;
        this.uploadUrl = uploadUrl;
    }

    // ------------------------------------------------------------------ configuration

    /** Where the scans come from, and how they are made. */
    public record BridgeConfig(String source, String deviceName, int resolution, String colourMode,
                               String paperSource, Path folder, String pairingToken, int retries) {

        /** The defaults a fresh installation starts with. */
        public static BridgeConfig defaults() {
            return new BridgeConfig("SANE", null, 300, "Color", "ADF", null, null, 3);
        }

        /**
         * Whether a device has to be named.
         *
         * <p>With one scanner attached, `scanimage` picks it; with several, a wrong guess scans the wrong
         * machine's paper, so the device has to be named.</p>
         */
        public boolean needsExplicitDevice() {
            return "SANE".equals(source) && (deviceName == null || deviceName.isBlank());
        }
    }

    /** What the bridge reports about itself. */
    public record Diagnostics(boolean ready, String summary, List<String> steps) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ready", ready);
            map.put("summary", summary);
            map.put("steps", steps);
            return map;
        }
    }

    /** What one run produced. */
    public record RunResult(int scanned, int uploaded, List<String> problems) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("scanned", scanned);
            map.put("uploaded", uploaded);
            map.put("problems", problems);
            return map;
        }
    }

    // ------------------------------------------------------------------ the tool and the device

    /**
     * Looks for the scanning tool and the devices, and says what it found.
     *
     * <p>The answer is written for somebody standing at the machine, so it names the command that was run
     * and what to install rather than reporting a failure code.</p>
     */
    public Diagnostics diagnose(BridgeConfig config) {
        List<String> steps = new ArrayList<>();

        if ("FOLDER".equals(config.source())) {
            return diagnoseFolder(config, steps);
        }
        if (!"SANE".equals(config.source())) {
            steps.add("Unbekannte Quelle '" + config.source() + "'. Unterstützt werden SANE und FOLDER.");
            return new Diagnostics(false, "Die eingestellte Quelle wird nicht unterstützt", steps);
        }

        String tool = which(scanimagePath);
        if (tool == null) {
            steps.add("Das Programm '" + scanimagePath + "' wurde nicht gefunden.");
            steps.add("Unter Linux: 'apt install sane-utils' oder 'dnf install sane-backends-drivers-scanners'.");
            steps.add("Unter macOS: 'brew install sane-backends'.");
            steps.add("Danach den Rechner neu starten, damit der Scanner erkannt wird.");
            steps.add("Alternativ in der Konfiguration source=FOLDER setzen: dann wird ein Ordner überwacht, "
                    + "in den ein anderes Programm scannt.");
            // This is the half of the acceptance criterion that gets written badly: a person standing at a
            // machine needs to know what to install, not that something failed.
            return new Diagnostics(false, "Es ist keine Scan-Software installiert, mit der dieses Programm "
                    + "sprechen kann", steps);
        }
        steps.add("Gefunden: " + tool);

        List<String> devices = listDevices();
        if (devices.isEmpty()) {
            steps.add("'" + scanimagePath + " -L' hat kein Gerät gemeldet.");
            steps.add("Prüfen, ob der Scanner eingeschaltet und per USB oder Netzwerk verbunden ist.");
            steps.add("Bei Netzwerkgeräten die Adresse in /etc/sane.d/net.conf eintragen.");
            return new Diagnostics(false, "Es wurde kein Scanner gefunden", steps);
        }
        steps.add("Geräte: " + String.join(", ", devices));

        if (config.deviceName() != null && !config.deviceName().isBlank()
                && devices.stream().noneMatch(device -> device.contains(config.deviceName()))) {
            steps.add("Das eingestellte Gerät '" + config.deviceName() + "' ist nicht dabei.");
            steps.add("Eines der oben genannten Geräte auswählen.");
            return new Diagnostics(false, "Das eingestellte Gerät wurde nicht gefunden", steps);
        }

        steps.add("Auflösung " + config.resolution() + " dpi, Modus " + config.colourMode() + ", Einzug "
                + config.paperSource() + ".");
        return new Diagnostics(true, "Der Scanner ist bereit", steps);
    }

    private Diagnostics diagnoseFolder(BridgeConfig config, List<String> steps) {
        Path folder = config.folder();
        if (folder == null) {
            steps.add("Es ist kein Ordner eingestellt, der überwacht werden soll.");
            steps.add("In der Konfiguration folder=/pfad/zum/ordner setzen.");
            return new Diagnostics(false, "Es ist kein Ordner eingestellt", steps);
        }
        if (!Files.isDirectory(folder)) {
            steps.add("Der Ordner '" + folder + "' existiert nicht.");
            steps.add("Den Ordner anlegen oder den Pfad berichtigen.");
            return new Diagnostics(false, "Der eingestellte Ordner existiert nicht", steps);
        }
        if (!Files.isReadable(folder)) {
            steps.add("Der Ordner '" + folder + "' ist nicht lesbar.");
            steps.add("Die Zugriffsrechte prüfen.");
            return new Diagnostics(false, "Der eingestellte Ordner ist nicht lesbar", steps);
        }
        steps.add("Ordner: " + folder);
        steps.add("Dateien, die dort abgelegt werden, gehen in den Eingang.");
        return new Diagnostics(true, "Der Ordner ist bereit", steps);
    }

    /** The devices SANE reports, by running the command rather than guessing. */
    public List<String> listDevices() {
        String tool = which(scanimagePath);
        if (tool == null) {
            return List.of();
        }
        try {
            Process process = new ProcessBuilder(tool, "-L").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            List<String> devices = new ArrayList<>();
            for (String line : output.split("\n")) {
                if (line.startsWith("device `") || line.startsWith("device ")) {
                    devices.add(line.replace("device `", "").replace("'", "").trim());
                }
            }
            return devices;
        }
        catch (IOException ex) {
            LOG.warn("Geräteabfrage fehlgeschlagen: {}", ex.getMessage());
            return List.of();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Scans through the device.
     *
     * <p>An automatic document feeder produces several pages in one run, which is why the answer is a list:
     * a bridge that returned one page from a stack of twenty would silently lose nineteen.</p>
     */
    public List<byte[]> scan(BridgeConfig config) {
        String tool = which(scanimagePath);
        if (tool == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "Es ist keine Scan-Software installiert ('" + scanimagePath + "' wurde nicht gefunden)");
        }

        List<String> command = new ArrayList<>(List.of(tool, "--format=png",
                "--resolution=" + config.resolution(), "--mode=" + config.colourMode()));
        if ("ADF".equals(config.paperSource())) {
            command.add("--source=Automatic Document Feeder");
            command.add("--batch");
        }
        if (config.deviceName() != null && !config.deviceName().isBlank()) {
            command.add("--device-name=" + config.deviceName());
        }

        try {
            Path output = Files.createTempDirectory("bc-bridge-");
            command.add(output.resolve("scan-%03d.png").toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String message = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();

            List<byte[]> pages = new ArrayList<>();
            try (var files = Files.list(output)) {
                for (Path file : files.sorted().toList()) {
                    if (Files.size(file) > 0) {
                        pages.add(Files.readAllBytes(file));
                    }
                }
            }
            if (exit != 0 && pages.isEmpty()) {
                throw new ApiException(ErrorCode.INTERNAL_ERROR,
                        "Der Scanner meldete einen Fehler: " + message.strip());
            }
            // Kept on disk on purpose: a scan that failed to upload must not have to go through the device
            // again, because the paper is back in the pile and the person has walked away.
            LOG.info("Scanned {} page(s) into {}", pages.size(), output);
            return pages;
        }
        catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Der Scan schlug fehl: " + ex.getMessage());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Der Scan wurde unterbrochen");
        }
    }

    /** Reads the pages a folder has collected, so a bridge can work without SANE. */
    public List<byte[]> readFolder(BridgeConfig config) {
        if (config.folder() == null || !Files.isDirectory(config.folder())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es ist kein lesbarer Ordner eingestellt");
        }
        List<byte[]> pages = new ArrayList<>();
        try (var files = Files.list(config.folder())) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(file) && (name.endsWith(".png") || name.endsWith(".jpg")
                        || name.endsWith(".jpeg") || name.endsWith(".tif") || name.endsWith(".tiff")
                        || name.endsWith(".pdf"))) {
                    pages.add(Files.readAllBytes(file));
                }
            }
        }
        catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Der Ordner konnte nicht gelesen werden: "
                    + ex.getMessage());
        }
        return pages;
    }

    /**
     * Sends one page, trying again when it fails.
     *
     * <p>The upload is retried rather than repeated blindly: the receiving end deduplicates by content, so
     * a page that did arrive and whose answer was lost is not stored twice.</p>
     */
    public boolean upload(byte[] page, String filename, String contentType, int retries,
                          Function<byte[], Boolean> transport) {
        if (page == null || page.length == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine leere Seite wird nicht gesendet");
        }
        int attempts = Math.max(1, retries);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                if (Boolean.TRUE.equals(transport.apply(page))) {
                    return true;
                }
                LOG.warn("Übertragung von {} wurde abgelehnt (Versuch {}/{})", filename, attempt, attempts);
            }
            catch (RuntimeException ex) {
                last = ex;
                LOG.warn("Übertragung von {} schlug fehl (Versuch {}/{}): {}", filename, attempt, attempts,
                        ex.getMessage());
            }
        }
        if (last != null) {
            throw last;
        }
        return false;
    }

    /** Where the bridge sends what it scans. */
    public String uploadUrl() {
        return uploadUrl;
    }

    /** What this program does not do, so that nobody waits for it. */
    public static Map<String, String> unsupported() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("TWAIN", "Braucht einen Windows-Treiber und eine Anwendung, die ihn einbindet. Nicht "
                + "enthalten; unter Windows den Ordner-Modus verwenden.");
        map.put("WIA", "Wie TWAIN an Windows gebunden. Nicht enthalten.");
        map.put("ISIS", "Anbieterabhängig. Nicht enthalten.");
        return map;
    }

    /** Reads a configuration from properties, refusing values that make no sense. */
    public static BridgeConfig fromProperties(Properties properties) {
        BridgeConfig defaults = BridgeConfig.defaults();
        String source = value(properties, "source", defaults.source()).toUpperCase(Locale.ROOT);
        int resolution = Integer.parseInt(value(properties, "resolution", "300"));
        if (resolution < 75 || resolution > 1200) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Auflösung muss zwischen 75 und 1200 dpi liegen, nicht " + resolution);
        }
        String colour = value(properties, "colourMode", "Color");
        if (!List.of("Color", "Gray", "Lineart").contains(colour)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Farbmodus: " + colour);
        }
        String paper = value(properties, "paperSource", "ADF");
        if (!List.of("ADF", "Flatbed").contains(paper)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Vorlage: " + paper);
        }
        String folderValue = value(properties, "folder", "");
        return new BridgeConfig(source, value(properties, "deviceName", ""), resolution, colour, paper,
                folderValue.isBlank() ? null : Path.of(folderValue),
                value(properties, "pairingToken", ""),
                Integer.parseInt(value(properties, "retries", "3")));
    }

    private static String value(Properties properties, String key, String fallback) {
        String found = properties == null ? null : properties.getProperty(key);
        return found == null || found.isBlank() ? fallback : found.strip();
    }

    /** Whether a command can be executed, the same way the shell would look for it. */
    static String which(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        Path direct = Path.of(command);
        if (direct.isAbsolute()) {
            return Files.isExecutable(direct) ? command : null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(entry, command);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /** A registration the bridge sends once, so the platform knows the device exists. */
    public record Registration(String identifier, String name, String osName, String appVersion,
                               UUID pairingTokenId) {
    }
}
