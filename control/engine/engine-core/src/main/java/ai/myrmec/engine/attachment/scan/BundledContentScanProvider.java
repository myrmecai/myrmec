package ai.myrmec.engine.attachment.scan;

import ai.myrmec.engine.spi.scan.ContentScanProvider;
import ai.myrmec.engine.spi.scan.ScanResult;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * Bundled, dependency-free {@link ContentScanProvider} (#104). It is a real
 * gate — not a no-op pass-through — that recognises the industry-standard
 * EICAR anti-malware test signature and a small set of executable magic
 * numbers, so the upload pipeline can be verified end-to-end without an
 * external AV engine.
 *
 * <p>Enterprise / air-gapped deployments contribute a bean fronting ClamAV
 * or a vendor API; this bean steps aside when another
 * {@link ContentScanProvider} is present (registered via
 * {@code @ConditionalOnMissingBean} in {@code AttachmentInfrastructureConfig}).</p>
 */
@Slf4j
public class BundledContentScanProvider implements ContentScanProvider {

    /** Standard EICAR anti-malware test file body. */
    private static final byte[] EICAR = (
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
            .getBytes(StandardCharsets.US_ASCII);

    @Override
    public ScanResult scan(byte[] content, String filename, String mediaType) {
        if (content == null || content.length == 0) {
            return ScanResult.clean(getId());
        }
        if (indexOf(content, EICAR) >= 0) {
            log.warn("Bundled scanner flagged EICAR test signature in '{}'", filename);
            return ScanResult.infected(getId(), "EICAR-Test-Signature");
        }
        String executable = detectExecutable(content);
        if (executable != null) {
            log.warn("Bundled scanner flagged executable magic ({}) in '{}'",
                    executable, filename);
            return ScanResult.infected(getId(), executable);
        }
        return ScanResult.clean(getId());
    }

    @Override
    public String getId() {
        return "bundled";
    }

    /** Recognise common executable container magic numbers, or null. */
    private static String detectExecutable(byte[] c) {
        if (c.length >= 2 && c[0] == 'M' && c[1] == 'Z') {
            return "Executable.PE-DOS";
        }
        if (c.length >= 4 && c[0] == 0x7F && c[1] == 'E' && c[2] == 'L' && c[3] == 'F') {
            return "Executable.ELF";
        }
        // Mach-O (0xFEEDFACE / 0xFEEDFACF, big-endian) and its 64-bit form.
        if (c.length >= 4 && (c[0] & 0xFF) == 0xFE && (c[1] & 0xFF) == 0xED
                && (c[2] & 0xFF) == 0xFA
                && ((c[3] & 0xFF) == 0xCE || (c[3] & 0xFF) == 0xCF)) {
            return "Executable.MachO";
        }
        return null;
    }

    /** Plain byte-array substring search (Knuth not warranted at these sizes). */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
