package br.carbonflow.pilot;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static br.carbonflow.pilot.EnergyImportModel.*;

final class EnergyCsv {
    record Plan(List<String> rows, int received, String rejection) {}

    static Plan plan(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            return new Plan(List.of(), countDataLines(new String(bytes, StandardCharsets.ISO_8859_1)),
                    "O arquivo bruto contém bytes incompatíveis com UTF-8.");
        }
        int size = countDataLines(text);
        if (size > MAX_LINES) {
            return new Plan(List.of(), size, "Limite de " + MAX_LINES + " linhas de dados. O lote inteiro foi recusado.");
        }
        if (text.indexOf(0) >= 0) {
            return new Plan(List.of(), size, "O arquivo bruto contém um caractere nulo.");
        }
        var lines = text.lines().toList();
        boolean headerValid;
        try {
            var headers = lines.isEmpty() ? List.<String>of()
                    : fields(lines.getFirst().replaceFirst("^\uFEFF", ""));
            var withEvidence = new ArrayList<>(HEADER);
            withEvidence.add("coverageEvidence");
            headerValid = headers.equals(HEADER) || headers.equals(withEvidence);
            if (!headerValid || size == 0) {
                return new Plan(List.of(), size, "Use o cabeçalho do mapeamento v1 e inclua pelo menos uma linha de dados.");
            }
            // A posição física é guardada. Uma linha em branco no meio do lote também entra na contagem.
            return new Plan(lines.subList(1, lines.size()), size, null);
        } catch (IllegalArgumentException error) {
            return new Plan(List.of(), size, "O cabeçalho CSV está malformado.");
        }
    }

    private static int countDataLines(String text) {
        return (int) Math.max(0, text.lines().count() - 1);
    }

    static int columnCount(byte[] bytes) {
        // Usado somente depois de o cabeçalho e o UTF-8 terem sido validados.
        String text = new String(bytes, StandardCharsets.UTF_8);
        return fields(text.lines().findFirst().orElseThrow().replaceFirst("^\uFEFF", "")).size();
    }

    static List<String> fields(String line) {
        var fields = new ArrayList<String>();
        int cursor = 0;
        do {
            if (cursor < line.length() && line.charAt(cursor) == '"') {
                cursor++;
                var value = new StringBuilder();
                boolean closed = false;
                while (cursor < line.length()) {
                    char symbol = line.charAt(cursor++);
                    if (symbol != '"') {
                        value.append(symbol);
                    } else if (cursor < line.length() && line.charAt(cursor) == '"') {
                        value.append('"');
                        cursor++;
                    } else {
                        closed = true;
                        break;
                    }
                }
                if (!closed || (cursor < line.length() && line.charAt(cursor) != ',')) {
                    throw new IllegalArgumentException("Aspas CSV inválidas ou campo ocupa mais de uma linha.");
                }
                fields.add(value.toString());
            } else {
                int comma = line.indexOf(',', cursor);
                if (comma < 0) comma = line.length();
                String value = line.substring(cursor, comma);
                if (value.indexOf('"') >= 0) throw new IllegalArgumentException("Aspas CSV inválidas.");
                fields.add(value);
                cursor = comma;
            }
            if (cursor == line.length()) break;
            cursor++;
        } while (true);
        return fields;
    }
}
