package br.carbonflow.pilot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class WordDocument {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    static byte[] render(String title, List<DossierText.Section> sections) {
        return render(title, null, sections);
    }

    static byte[] render(String title, String reference, List<DossierText.Section> sections) {
        var document = new StringBuilder("<w:document xmlns:w=\"" + W + "\"><w:body>");
        document.append(paragraph("Title", title));
        document.append(paragraph("Warning", "SIMULAÇÃO   SIMULATION   Synthetic pilot"));
        if (reference != null) document.append(paragraph("Reference", reference));
        for (var section : sections) {
            document.append(paragraph("Heading1", section.heading())).append(paragraph("Normal", section.text()));
            if (!section.table().isEmpty()) document.append(table(section.table()));
        }
        document.append("""
                <w:sectPr><w:pgSz w:w="11906" w:h="16838"/>
                <w:pgMar w:top="1100" w:right="1100" w:bottom="1100" w:left="1100"
                    w:header="500" w:footer="500" w:gutter="0"/></w:sectPr></w:body></w:document>
                """);
        return DossierZip.pack(Map.of(
                "[Content_Types].xml", bytes("""
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                      <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                    </Types>"""),
                "_rels/.rels", bytes("""
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>"""),
                "word/_rels/document.xml.rels", bytes("""
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>"""),
                "word/styles.xml", bytes(styles()), "word/document.xml", bytes(document.toString())));
    }

    private static String styles() {
        return """
                <w:styles xmlns:w="%s">
                <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/>
                  <w:lang w:val="en-US"/><w:color w:val="000000"/><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>
                <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/>
                  <w:pPr><w:spacing w:after="135" w:line="276" w:lineRule="auto"/></w:pPr></w:style>
                <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/>
                  <w:pPr><w:keepNext/><w:spacing w:before="0" w:after="150"/></w:pPr>
                  <w:rPr><w:b/><w:sz w:val="50"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
                  <w:pPr><w:keepNext/><w:spacing w:before="190" w:after="60"/><w:outlineLvl w:val="0"/></w:pPr>
                  <w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Warning"><w:name w:val="Simulation notice"/><w:basedOn w:val="Normal"/>
                  <w:pPr><w:keepNext/><w:spacing w:after="260"/></w:pPr>
                  <w:rPr><w:b/><w:sz w:val="19"/><w:color w:val="5B2932"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Reference"><w:name w:val="Dossier reference"/><w:basedOn w:val="Normal"/>
                  <w:pPr><w:keepNext/><w:spacing w:after="170"/></w:pPr><w:rPr><w:sz w:val="20"/></w:rPr></w:style>
                <w:style w:type="paragraph" w:styleId="Cell"><w:name w:val="Table cell"/><w:basedOn w:val="Normal"/>
                  <w:pPr><w:spacing w:after="0" w:line="250" w:lineRule="auto"/></w:pPr>
                  <w:rPr><w:sz w:val="20"/></w:rPr></w:style>
                </w:styles>
                """.formatted(W);
    }

    private static String table(List<List<String>> rows) {
        int count = rows.getFirst().size();
        int dateWidth = 4050;
        int valueWidth = (9700 - dateWidth) / (count - 1);
        var table = new StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"9700\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/>");
        table.append("<w:tblBorders>");
        for (String edge : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
            table.append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"4\" w:color=\"D9D9D9\"/>");
        }
        table.append("</w:tblBorders><w:tblCellMar><w:top w:w=\"80\" w:type=\"dxa\"/><w:bottom w:w=\"80\" w:type=\"dxa\"/>")
                .append("<w:left w:w=\"100\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>");
        for (int i = 0; i < count; i++) table.append("<w:gridCol w:w=\"").append(i == 0 ? dateWidth : valueWidth).append("\"/>");
        table.append("</w:tblGrid>");
        for (int row = 0; row < rows.size(); row++) {
            table.append("<w:tr><w:trPr><w:cantSplit/>").append(row == 0 ? "<w:tblHeader/>" : "").append("</w:trPr>");
            for (String cell : rows.get(row)) {
                table.append("<w:tc><w:tcPr><w:vAlign w:val=\"center\"/>");
                if (row == 0) table.append("<w:shd w:fill=\"E8EDF1\"/>");
                table.append("</w:tcPr>").append(paragraph("Cell", cell)).append("</w:tc>");
            }
            table.append("</w:tr>");
        }
        return table.append("</w:tbl>").toString();
    }

    private static String paragraph(String style, String value) {
        return "<w:p><w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr><w:r><w:t xml:space=\"preserve\">"
                + xml(value) + "</w:t></w:r></w:p>";
    }

    private static String xml(String value) {
        return value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]", "")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}

final class DossierZip {
    static byte[] pack(Map<String, byte[]> files) {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var path : files.keySet().stream().sorted().toList()) {
                var entry = new ZipEntry(path);
                entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0));
                zip.putNextEntry(entry);
                zip.write(files.get(path));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao montar pacote documentário", e);
        }
        return output.toByteArray();
    }

    private DossierZip() {}
}
