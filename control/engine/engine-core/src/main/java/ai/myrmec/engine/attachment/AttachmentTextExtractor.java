// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.attachment;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts inline-friendly plain text from attachment payloads.
 */
final class AttachmentTextExtractor {

    private static final Set<String> PLAIN_TEXT = Set.of("txt", "text", "md", "markdown");
    private static final Set<String> HTML = Set.of("html", "htm");
    private static final String PDF = "pdf";
    private static final String DOCX = "docx";
    private static final String XML = "xml";
    private static final String JSON = "json";
    private static final String DOCX_BODY_ENTRY = "word/document.xml";
    private static final Pattern DOCX_PARAGRAPH_END = Pattern.compile("(?i)</w:p>");
    private static final Pattern XML_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private AttachmentTextExtractor() {
    }

    static Optional<ExtractedText> extract(String filename, String mediaType, byte[] bytes) throws IOException {
        String ext = extension(filename);
        if (mediaType != null && mediaType.startsWith("text/")) {
            return Optional.of(fromPlainText(new String(bytes, StandardCharsets.UTF_8)));
        }
        if (PLAIN_TEXT.contains(ext)) {
            return Optional.of(fromPlainText(new String(bytes, StandardCharsets.UTF_8)));
        }
        if (HTML.contains(ext)) {
            return Optional.of(fromPlainText(stripHtml(new String(bytes, StandardCharsets.UTF_8))));
        }
        if (PDF.equals(ext) || "application/pdf".equals(mediaType)) {
            return Optional.of(fromPlainText(extractPdf(bytes)));
        }
        if (DOCX.equals(ext)) {
            return Optional.of(fromPlainText(extractDocx(bytes)));
        }
        if (JSON.equals(ext) || XML.equals(ext)) {
            return Optional.of(fromPlainText(new String(bytes, StandardCharsets.UTF_8)));
        }
        return Optional.empty();
    }

    static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    private static ExtractedText fromPlainText(String text) {
        String trimmed = text == null ? null : text.strip();
        if (trimmed == null || trimmed.isBlank()) {
            return new ExtractedText(null, null);
        }
        return new ExtractedText(trimmed, estimateTokens(trimmed));
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int dot = filename.lastIndexOf('.');
        if (dot <= slash || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripHtml(String html) {
        return html.replaceAll("(?is)<script.*?</script>", "")
                .replaceAll("(?is)<style.*?</style>", "")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s+", "\\n")
                .strip();
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

    private static String docxXmlToText(String xml) {
        String withBreaks = DOCX_PARAGRAPH_END.matcher(xml).replaceAll("\n");
        String stripped = XML_TAG.matcher(withBreaks).replaceAll("");
        String decoded = stripped
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        StringBuilder out = new StringBuilder();
        for (String line : decoded.split("\n", -1)) {
            String collapsed = WHITESPACE_RUN.matcher(line).replaceAll(" ").strip();
            if (!collapsed.isEmpty()) {
                out.append(collapsed).append('\n');
            }
        }
        return out.toString().strip();
    }

    record ExtractedText(String text, Integer tokenEstimate) {
    }
}
