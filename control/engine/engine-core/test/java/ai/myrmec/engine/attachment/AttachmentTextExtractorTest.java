// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.attachment;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentTextExtractorTest {

    @Test
    void extractsPlainTextAndEstimatesTokens() throws Exception {
        Optional<AttachmentTextExtractor.ExtractedText> extracted =
                AttachmentTextExtractor.extract("notes.txt", "text/plain", "hello world".getBytes(StandardCharsets.UTF_8));

        assertThat(extracted).isPresent();
        assertThat(extracted.get().text()).isEqualTo("hello world");
        assertThat(extracted.get().tokenEstimate()).isGreaterThan(0);
    }

    @Test
    void extractsDocxBodyText() throws Exception {
        byte[] docx = docxBytes("alpha", "beta");

        Optional<AttachmentTextExtractor.ExtractedText> extracted =
                AttachmentTextExtractor.extract("report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        assertThat(extracted).isPresent();
        assertThat(extracted.get().text()).isEqualTo("alpha\nbeta");
    }

    @Test
    void ignoresUnsupportedTypes() throws Exception {
        Optional<AttachmentTextExtractor.ExtractedText> extracted =
                AttachmentTextExtractor.extract("binary.bin", "application/octet-stream", new byte[] {1, 2, 3});

        assertThat(extracted).isEmpty();
    }

    private static byte[] docxBytes(String... paragraphs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(docxXml(paragraphs).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static String docxXml(String... paragraphs) {
        StringBuilder xml = new StringBuilder();
        xml.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        for (String paragraph : paragraphs) {
            xml.append("<w:p><w:r><w:t>").append(paragraph).append("</w:t></w:r></w:p>");
        }
        xml.append("</w:body></w:document>");
        return xml.toString();
    }
}
