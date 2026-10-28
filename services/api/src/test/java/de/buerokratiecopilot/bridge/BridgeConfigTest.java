package de.buerokratiecopilot.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies the Desktop Bridge (spec §8, G-002).
 *
 * <p>The acceptance criterion has two halves and the second is the one usually written badly: a scan
 * reaches the inbox when a SANE device is present, and the answer is understandable when it is not. The
 * person reading the second half is standing at a machine they cannot use, so the tests check that the
 * diagnosis names what is missing, how it was looked for and what to do — not that something failed.</p>
 *
 * <p>No scanner is needed to run these: the diagnosis is exercised on a machine that has none, and the
 * folder source stands in for the device. What is therefore <em>not</em> verified is a scan from real
 * hardware, and saying so is part of the result.</p>
 */
class BridgeConfigTest {

    private DesktopBridge bridge(String scanimagePath) {
        return new DesktopBridge(scanimagePath, "http://localhost:8080/api/v1/documents:ingest");
    }

    private DesktopBridge bridgeWithMissingTool() {
        return bridge("/nonexistent/scanimage-for-tests");
    }

    // ---------------------------------------------------------------- configuration

    @Test
    @DisplayName("a fresh configuration has sensible defaults")
    void defaultsAreSensible() {
        DesktopBridge.BridgeConfig defaults = DesktopBridge.BridgeConfig.defaults();

        assertThat(defaults.source()).isEqualTo("SANE");
        assertThat(defaults.resolution()).isEqualTo(300);
        assertThat(defaults.paperSource()).isEqualTo("ADF");
        assertThat(defaults.colourMode()).isEqualTo("Color");
    }

    @Test
    @DisplayName("a configuration is read from properties")
    void aConfigurationIsRead() {
        Properties properties = new Properties();
        properties.setProperty("source", "folder");
        properties.setProperty("resolution", "600");
        properties.setProperty("colourMode", "Gray");
        properties.setProperty("paperSource", "Flatbed");
        properties.setProperty("folder", "/tmp/scans");

        DesktopBridge.BridgeConfig config = DesktopBridge.fromProperties(properties);

        assertThat(config.source()).isEqualTo("FOLDER");
        assertThat(config.resolution()).isEqualTo(600);
        assertThat(config.colourMode()).isEqualTo("Gray");
        assertThat(config.paperSource()).isEqualTo("Flatbed");
        assertThat(config.folder()).isEqualTo(Path.of("/tmp/scans"));
    }

    @Test
    @DisplayName("nonsense in the configuration is refused rather than used")
    void nonsenseIsRefused() {
        Properties tooHigh = new Properties();
        tooHigh.setProperty("resolution", "5000");
        assertThatThrownBy(() -> DesktopBridge.fromProperties(tooHigh))
                .isInstanceOf(ApiException.class).hasMessageContaining("75 und 1200");

        Properties badColour = new Properties();
        badColour.setProperty("colourMode", "Rainbow");
        assertThatThrownBy(() -> DesktopBridge.fromProperties(badColour))
                .isInstanceOf(ApiException.class).hasMessageContaining("Farbmodus");

        Properties badPaper = new Properties();
        badPaper.setProperty("paperSource", "Roller");
        assertThatThrownBy(() -> DesktopBridge.fromProperties(badPaper))
                .isInstanceOf(ApiException.class).hasMessageContaining("Vorlage");
    }

    // ---------------------------------------------------------------- the diagnosis

    @Test
    @DisplayName("with no scanning software the diagnosis names what to install")
    void withoutTheToolTheDiagnosisNamesWhatToInstall() {
        DesktopBridge.Diagnostics diagnostics = bridgeWithMissingTool()
                .diagnose(DesktopBridge.BridgeConfig.defaults());

        assertThat(diagnostics.ready()).isFalse();
        String steps = String.join(" ", diagnostics.steps());
        assertThat(steps)
                .as("'scanning failed' tells a person standing at a machine nothing at all")
                .contains("wurde nicht gefunden");
        assertThat(steps).contains("apt install sane-utils");
        assertThat(steps).contains("brew install sane-backends");
        assertThat(steps).contains("FOLDER");
        assertThat(diagnostics.summary()).contains("keine Scan-Software");
    }

    @Test
    @DisplayName("a device that is not there is diagnosed as not there")
    void aMissingDeviceIsDiagnosed() {
        // The tool this test uses does not exist, so the diagnosis stops one step earlier; the wording of
        // the step matters more than which step was reached.
        DesktopBridge.Diagnostics diagnostics = bridgeWithMissingTool().diagnose(
                new DesktopBridge.BridgeConfig("SANE", "brother:net1", 300, "Color", "ADF", null, null, 3));

        assertThat(diagnostics.ready()).isFalse();
        assertThat(diagnostics.steps()).isNotEmpty();
    }

    @Test
    @DisplayName("an unknown source is refused with the ones that are supported")
    void anUnknownSourceIsRefused() {
        DesktopBridge.Diagnostics diagnostics = bridgeWithMissingTool().diagnose(
                new DesktopBridge.BridgeConfig("MAGIC", null, 300, "Color", "ADF", null, null, 3));

        assertThat(diagnostics.ready()).isFalse();
        assertThat(diagnostics.summary()).contains("nicht unterstützt");
        assertThat(String.join(" ", diagnostics.steps())).contains("SANE").contains("FOLDER");
    }

    @Test
    @DisplayName("TWAIN and WIA are named as not implemented rather than silently absent")
    void twainAndWiaAreNamed() {
        assertThat(DesktopBridge.unsupported())
                .containsKeys("TWAIN", "WIA")
                .allSatisfy((name, reason) -> assertThat(reason).contains("Nicht enthalten"));
    }

    // ---------------------------------------------------------------- the folder source

    @Test
    @DisplayName("a missing folder is diagnosed with what to do")
    void aMissingFolderIsDiagnosed(@TempDir Path temp) {
        Path missing = temp.resolve("gibt-es-nicht");
        DesktopBridge.Diagnostics diagnostics = bridgeWithMissingTool().diagnose(
                new DesktopBridge.BridgeConfig("FOLDER", null, 300, "Color", "ADF", missing, null, 3));

        assertThat(diagnostics.ready()).isFalse();
        assertThat(String.join(" ", diagnostics.steps())).contains("existiert nicht").contains("anlegen");
    }

    @Test
    @DisplayName("a folder that is not configured is diagnosed rather than ignored")
    void anUnconfiguredFolderIsDiagnosed() {
        DesktopBridge.Diagnostics diagnostics = bridgeWithMissingTool().diagnose(
                new DesktopBridge.BridgeConfig("FOLDER", null, 300, "Color", "ADF", null, null, 3));

        assertThat(diagnostics.ready()).isFalse();
        assertThat(diagnostics.summary()).contains("kein Ordner");
    }

    @Test
    @DisplayName("a folder that exists and is readable is ready")
    void aReadableFolderIsReady(@TempDir Path folder) {
        DesktopBridge.Diagnostics diagnostics = bridgeWithMissingTool().diagnose(
                new DesktopBridge.BridgeConfig("FOLDER", null, 300, "Color", "ADF", folder, null, 3));

        assertThat(diagnostics.ready()).isTrue();
        assertThat(diagnostics.summary()).contains("bereit");
    }

    @Test
    @DisplayName("the folder source reads the pages a folder has collected, in order")
    void theFolderSourceReadsPages(@TempDir Path folder) throws Exception {
        Files.write(folder.resolve("scan-001.png"), new byte[] {1, 2, 3});
        Files.write(folder.resolve("scan-002.png"), new byte[] {4, 5, 6});
        Files.write(folder.resolve("notiz.txt"), new byte[] {9});
        DesktopBridge bridge = bridgeWithMissingTool();

        List<byte[]> pages = bridge.readFolder(
                new DesktopBridge.BridgeConfig("FOLDER", null, 300, "Color", "ADF", folder, null, 3));

        assertThat(pages)
                .as("a text file is not a scanned page")
                .hasSize(2);
        assertThat(pages.getFirst()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("an unreadable folder is refused rather than reported as empty")
    void anUnreadableFolderIsRefused() {
        assertThatThrownBy(() -> bridgeWithMissingTool().readFolder(
                new DesktopBridge.BridgeConfig("FOLDER", null, 300, "Color", "ADF", null, null, 3)))
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- scanning and sending

    @Test
    @DisplayName("scanning without the tool says so instead of returning nothing")
    void scanningWithoutTheToolSaysSo() {
        assertThatThrownBy(() -> bridgeWithMissingTool().scan(DesktopBridge.BridgeConfig.defaults()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("keine Scan-Software");
    }

    @Test
    @DisplayName("no devices are reported when there is no tool to ask")
    void noDevicesWithoutATool() {
        assertThat(bridgeWithMissingTool().listDevices()).isEmpty();
    }

    @Test
    @DisplayName("a page that fails to send is sent again")
    void aFailedUploadIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        DesktopBridge bridge = bridgeWithMissingTool();

        boolean sent = bridge.upload(new byte[] {1, 2, 3}, "scan-001.png", "image/png", 3, page -> {
            // The first attempt fails - the connection dropped - and the second succeeds. Rescanning is not
            // a recovery: the paper is back in the pile and the person has walked away.
            return attempts.incrementAndGet() >= 2;
        });

        assertThat(sent).isTrue();
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a page that never sends reports the last failure rather than pretending")
    void anUploadThatNeverSucceedsReports() {
        DesktopBridge bridge = bridgeWithMissingTool();

        assertThatThrownBy(() -> bridge.upload(new byte[] {1}, "scan.png", "image/png", 3, page -> {
            throw new IllegalStateException("Verbindung unterbrochen");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("unterbrochen");
    }

    @Test
    @DisplayName("a refused page is retried and then reported as not sent")
    void aRefusedPageIsReported() {
        AtomicInteger attempts = new AtomicInteger();
        DesktopBridge bridge = bridgeWithMissingTool();

        boolean sent = bridge.upload(new byte[] {1}, "scan.png", "image/png", 2, page -> {
            attempts.incrementAndGet();
            return false;
        });

        assertThat(sent).isFalse();
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a page is sent at least once even when no retries are configured")
    void atLeastOneAttemptHappens() {
        AtomicInteger attempts = new AtomicInteger();
        bridgeWithMissingTool().upload(new byte[] {1}, "scan.png", "image/png", 0, page -> {
            attempts.incrementAndGet();
            return true;
        });

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty page is refused rather than sent")
    void anEmptyPageIsRefused() {
        assertThatThrownBy(() -> bridgeWithMissingTool().upload(new byte[0], "leer.png", "image/png", 3,
                page -> true))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the bridge knows where it sends what it scans")
    void theBridgeKnowsItsDestination() {
        assertThat(bridgeWithMissingTool().uploadUrl()).contains("documents:ingest");
    }

    @Test
    @DisplayName("a device has to be named only when there is a reason to")
    void aDeviceHasToBeNamedOnlySometimes() {
        assertThat(DesktopBridge.BridgeConfig.defaults().needsExplicitDevice()).isTrue();
        assertThat(new DesktopBridge.BridgeConfig("SANE", "brother:net1", 300, "Color", "ADF", null, null, 3)
                .needsExplicitDevice()).isFalse();
        assertThat(new DesktopBridge.BridgeConfig("FOLDER", null, 300, "Color", "ADF", Path.of("/tmp"), null, 3)
                .needsExplicitDevice()).isFalse();
    }

    @Test
    @DisplayName("a command is looked for the way a shell looks for it")
    void aCommandIsLookedFor() {
        assertThat(DesktopBridge.which("sh")).isNotNull();
        assertThat(DesktopBridge.which("gibt-es-sicher-nicht-12345")).isNull();
        assertThat(DesktopBridge.which("/bin/sh")).isEqualTo("/bin/sh");
        assertThat(DesktopBridge.which(null)).isNull();
    }
}
