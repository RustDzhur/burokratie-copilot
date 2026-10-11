package de.buerokratiecopilot.einvoicing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.einvoicing.domain.EInvoiceRecord;
import de.buerokratiecopilot.shared.storage.BlobStore;
import de.buerokratiecopilot.support.TestDocuments;

/**
 * Verifies that an electronic invoice is stored as it arrived (spec §14, D-005 acceptance).
 *
 * <p>The acceptance criterion is that the original XML is preserved unchanged. Read back through the
 * service and compared byte for byte, it is also the check that the storage path works end to end:
 * object storage, the blob row, and the record that points at it.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class EInvoiceStorageIT {

    @Autowired
    private EInvoiceService eInvoices;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BlobStore blobStore;

    private static final String UBL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                     xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
              <cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:xoev-de:kosit:standard:xrechnung_3.0</cbc:CustomizationID>
              <cbc:ID>RE-2026-902</cbc:ID>
              <cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
              <cbc:DueDate>2026-03-31</cbc:DueDate>
            </Invoice>
            """;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'E-Rechnung Test', 'COMPANY') returning id
                """, UUID.class, "ein-" + UUID.randomUUID().toString().substring(0, 12));
    }

    /** A PDF carrying the invoice XML, the way ZUGFeRD does. */
    private static byte[] pdfWith(String filename, byte[] content) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDEmbeddedFile embedded = new PDEmbeddedFile(document, new java.io.ByteArrayInputStream(content));
            embedded.setSize(content.length);
            embedded.setSubtype("text/xml");

            PDComplexFileSpecification specification = new PDComplexFileSpecification();
            specification.setFile(filename);
            specification.setEmbeddedFile(embedded);

            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(java.util.Map.of(filename, specification));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            document.getDocumentCatalog().setNames(names);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("an XML invoice is stored and read back byte for byte")
    void storesTheOriginalUnchanged() throws Exception {
        UUID tenantId = tenant();
        byte[] xml = UBL.getBytes(StandardCharsets.UTF_8);

        EInvoiceService.Imported imported = eInvoices.importFile(tenantId, null, xml, "rechnung.xml", null);

        assertThat(imported.accepted()).isTrue();
        assertThat(imported.record().getFormat()).isEqualTo(EInvoiceRecord.Format.XRECHNUNG);
        assertThat(imported.record().getProfile()).isEqualTo("XRECHNUNG");
        assertThat(imported.record().getValidationStatus())
                .isEqualTo(EInvoiceRecord.ValidationStatus.VALID);
        assertThat(imported.parsed().invoiceNumber()).isEqualTo("RE-2026-902");

        // The acceptance criterion, checked through the service rather than in memory.
        byte[] stored = eInvoices.originalXml(imported.record().getId(), tenantId);
        assertThat(stored).isEqualTo(xml);
    }

    @Test
    @DisplayName("the XML of a ZUGFeRD PDF is extracted, stored and readable")
    void storesXmlFromPdf() throws Exception {
        UUID tenantId = tenant();
        byte[] xml = UBL.getBytes(StandardCharsets.UTF_8);
        byte[] pdf = pdfWith("zugferd.xml", xml);

        EInvoiceService.Imported imported = eInvoices.importFile(tenantId, null, pdf, "rechnung.pdf",
                null);

        assertThat(imported.accepted()).isTrue();
        assertThat(imported.record().getFormat()).isEqualTo(EInvoiceRecord.Format.ZUGFERD);
        assertThat(eInvoices.originalXml(imported.record().getId(), tenantId)).isEqualTo(xml);
    }

    @Test
    @DisplayName("a PDF without XML produces no e-invoice record at all")
    void pdfWithoutXmlCreatesNoRecord() throws Exception {
        UUID tenantId = tenant();
        byte[] plainPdf = TestDocuments.pdf("Rechnung 2026-903\nBetrag: 119,00 EUR");

        EInvoiceService.Imported imported = eInvoices.importFile(tenantId, null, plainPdf, "rechnung.pdf",
                null);

        assertThat(imported.accepted()).isFalse();
        assertThat(imported.reason()).contains("kein Rechnungs-XML");
        assertThat(eInvoices.needingAttention(tenantId))
                .as("a document must not appear in a list of machine-readable invoices")
                .isEmpty();
    }

    @Test
    @DisplayName("a syntax the platform cannot read is recorded as unsupported, and kept")
    void unsupportedSyntaxIsRecorded() throws Exception {
        UUID tenantId = tenant();
        byte[] foreign = "<Rechnung><Nummer>1</Nummer></Rechnung>".getBytes(StandardCharsets.UTF_8);

        EInvoiceService.Imported imported = eInvoices.importFile(tenantId, null, foreign, "fremd.xml",
                null);

        assertThat(imported.accepted()).isTrue();
        assertThat(imported.record().getValidationStatus())
                .isEqualTo(EInvoiceRecord.ValidationStatus.NOT_SUPPORTED);
        assertThat(imported.record().isSupported()).isFalse();
        // The operator has to be able to see that a supplier sends something the platform cannot read.
        assertThat(eInvoices.needingAttention(tenantId)).hasSize(1);
        assertThat(eInvoices.originalXml(imported.record().getId(), tenantId)).isEqualTo(foreign);
    }

    @Test
    @DisplayName("the stored XML is registered as a document blob, so the record can point at it")
    void xmlIsRegisteredAsABlob() throws Exception {
        UUID tenantId = tenant();

        EInvoiceService.Imported imported = eInvoices.importFile(tenantId, null,
                UBL.getBytes(StandardCharsets.UTF_8), "rechnung.xml", null);

        String storageKey = jdbc.queryForObject(
                "select storage_key from document_blobs where id = ?", String.class,
                imported.record().getOriginalXmlBlobId());
        assertThat(storageKey).isNotBlank();
        assertThat(blobStore.exists(storageKey))
                .as("the object the row points at really exists in the store")
                .isTrue();
    }

    @Test
    @DisplayName("a file that is not readable is refused instead of stored as an empty record")
    void refusesEmptyFile() {
        UUID tenantId = tenant();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> eInvoices.importFile(tenantId, null, new byte[0], "leer.xml", null)))
                .isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);
    }

    @Test
    @DisplayName("a record of another tenant is not found")
    void otherTenantSeesNothing() throws Exception {
        UUID owner = tenant();
        UUID stranger = tenant();
        EInvoiceService.Imported imported = eInvoices.importFile(owner, null,
                UBL.getBytes(StandardCharsets.UTF_8), "rechnung.xml", null);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> eInvoices.originalXml(imported.record().getId(), stranger)))
                .isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);
    }
}
