package de.buerokratiecopilot.documents.ocr;

import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Selects the OCR engine (spec §9.2: the choice of engine is configurable).
 *
 * <p>{@code none} is a supported configuration: the pipeline then records the pages without a text
 * layer as "not examined" and routes the document to human review, instead of pretending a scan was
 * read. A configured engine that cannot be executed is reported at startup rather than discovered
 * when the first scan arrives.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OcrConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OcrConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(OcrEngine.class)
    public OcrEngine ocrEngine(BurokratieProperties properties) {
        BurokratieProperties.Ocr config = properties.getOcr();
        String engine = config.getEngine() == null ? "none" : config.getEngine().trim().toLowerCase(Locale.ROOT);

        if ("tesseract".equals(engine) && config.isEnabled()) {
            TesseractOcrEngine tesseract = new TesseractOcrEngine(config.getTesseractPath(),
                    Duration.ofMinutes(2));
            if (!tesseract.isAvailable()) {
                throw new IllegalStateException(
                        "burokratie.ocr.engine=tesseract, but no OCR engine with usable language data "
                        + "was found at " + config.getTesseractPath()
                        + " (install tesseract and its language packages, or set BC_OCR_ENABLED=false)");
            }
            log.info("OCR is active through {} with languages {}", tesseract.name(),
                    tesseract.availableLanguages());
            return tesseract;
        }

        log.info("No OCR engine is configured: pages without a text layer are recorded as not examined");
        return new NoOpOcrEngine();
    }
}
