package ai.myrmec.engine.knowledge.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts retrievable plain text from the document formats the object-storage
 * connector ingests (#22): {@code txt}/{@code md}/{@code text}, {@code html},
 * {@code pdf}, and {@code docx}.
 *
 * <p>Kept deliberately lightweight: plain-text and HTML use the JDK + the
 * shared {@link WebCrawlConnector#htmlToText(String)} helper, PDF uses Apache
 * PDFBox, and DOCX is unzipped with the JDK and stripped of its WordprocessingML
 * tags — no Apache POI / Tika needed. Unsupported extensions return
 * {@code null} so the caller can skip them without downloading-then-failing.</p>
 */
final class TextExtractor {

    private static final Set<String> PLAIN_TEXT = Set.of("txt", "text", "md", "markdown");
    private static final Set<String> HTML = Set.of("html", "htm");
    private static final String PDF = "pdf";
    private static final String DOCX = "docx";

    /** Entry inside a .docx zip that carries the body text. */
    private static final String DOCX_BODY_ENTRY = "word/document.xml";

    /** Paragraph close tag — converted to a newline before tag stripping. */
    private static final Pattern DOCX_PARAGRAPH_END = Pattern.compile("(?i)</w:p>");
    private static final Pattern XML_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private TextExtractor() {
    }

    /** Lower-cased extension of {@code key} after the last dot, or {@code ""}. */
    static String extension(String key) {
        if (key == null) {
            return "";
        }
        int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
        int dot = key.lastIndexOf('.');
        if (dot <= slash || dot == key.length() - 1) {
            return "";
        }
        return key.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** True when this connector knows how to turn {@code key} into text. */
    static boolean isSupported(String key) {
        String ext = extension(key);
        return PLAIN_TEXT.contains(ext) || HTML.contains(ext) || PDF.equals(ext) || DOCX.equals(ext);
    }

    /**
     * Extract plain text from {@code bytes} based on the {@code key}'s
     * extension. Returns {@code null} for unsupported types.
     *
     * @throws IOException when a supported document is malformed/unreadable.
     */
    static String extract(String key, byte[] bytes) throws IOException {
        String ext = extension(key);
        if (PLAIN_TEXT.contains(ext)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (HTML.contains(ext)) {
            return WebCrawlConnector.htmlToText(new String(bytes, StandardCharsets.UTF_8));
        }
        if (PDF.equals(ext)) {
            return extractPdf(bytes);
        }
        if (DOCX.equals(ext)) {
            return extractDocx(bytes);
        }
        return null;
    }

    private static String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            return text == null ? "" : text.strip();
        }
    }

    private static String extractDocx(byte[] bytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (DOCX_BODY_ENTRY.equals(entry.getName())) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    return docxXmlToText(xml);
                }
            }
        }
        return "";
    }

    /** Turn WordprocessingML body XML into newline-separated paragraph text. */
    private static String docxXmlToText(String xml) {
        String withBreaks = DOCX_PARAGRAPH_END.matcher(xml).replaceAll("\n");
        String stripped = XML_TAG.matcher(withBreaks).replaceAll("");
        String decoded = stripped
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        // Collapse intra-line whitespace but keep paragraph newlines.
        StringBuilder out = new StringBuilder();
        for (String line : decoded.split("\n", -1)) {
            String collapsed = WHITESPACE_RUN.matcher(line).replaceAll(" ").strip();
            if (!collapsed.isEmpty()) {
                out.append(collapsed).append('\n');
            }
        }
        return out.toString().strip();
    }
}
