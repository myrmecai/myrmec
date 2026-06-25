package ai.myrmec.engine.attachment.scan;

import ai.myrmec.engine.spi.scan.ScanResult;
import ai.myrmec.engine.spi.scan.ScanVerdict;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure in-memory unit test for the bundled scanner (#104). Runs the EICAR
 * signature entirely in memory (no multipart, no temp file) so it is not
 * intercepted by host antivirus, and verifies the executable-magic and
 * clean paths.
 */
class BundledContentScanProviderTest {

    private static final String EICAR =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private final BundledContentScanProvider scanner = new BundledContentScanProvider();

    @Test
    void detectsEicarSignature() {
        ScanResult result = scanner.scan(
                EICAR.getBytes(StandardCharsets.US_ASCII), "sample.txt", "text/plain");

        assertThat(result.verdict()).isEqualTo(ScanVerdict.INFECTED);
        assertThat(result.threat()).isEqualTo("EICAR-Test-Signature");
        assertThat(result.isClean()).isFalse();
        assertThat(result.scannerId()).isEqualTo("bundled");
    }

    @Test
    void detectsPeExecutableMagic() {
        ScanResult result = scanner.scan(
                new byte[]{'M', 'Z', 0x10, 0x20}, "report.pdf", "application/pdf");

        assertThat(result.verdict()).isEqualTo(ScanVerdict.INFECTED);
        assertThat(result.threat()).isEqualTo("Executable.PE-DOS");
    }

    @Test
    void detectsElfExecutableMagic() {
        ScanResult result = scanner.scan(
                new byte[]{0x7F, 'E', 'L', 'F', 0x01}, "doc.pdf", "application/pdf");

        assertThat(result.verdict()).isEqualTo(ScanVerdict.INFECTED);
        assertThat(result.threat()).isEqualTo("Executable.ELF");
    }

    @Test
    void passesCleanContent() {
        ScanResult result = scanner.scan(
                "the quick brown fox".getBytes(StandardCharsets.UTF_8),
                "notes.txt", "text/plain");

        assertThat(result.verdict()).isEqualTo(ScanVerdict.CLEAN);
        assertThat(result.isClean()).isTrue();
    }

    @Test
    void treatsEmptyContentAsClean() {
        assertThat(scanner.scan(new byte[0], "empty.txt", "text/plain").isClean()).isTrue();
    }
}
