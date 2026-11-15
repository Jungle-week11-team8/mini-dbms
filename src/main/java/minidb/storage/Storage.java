package minidb.storage;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import minidb.DbException;

/**
 * data 디렉터리의 &lt;table&gt;.schema(JSON)와 &lt;table&gt;.csv(헤더 없는 데이터)를 다룬다.
 * table 이름은 lexer가 [A-Za-z0-9_]만 허용하므로 경로 조작이 불가능하다.
 */
public final class Storage {
    /** type은 INT 또는 TEXT. */
    public record Column(String name, String type) {}

    public record Schema(List<Column> columns) {}

    private Storage() {}

    public static Schema loadSchema(Path dir, String table) throws DbException {
        // macOS/Windows 파일시스템은 대소문자를 무시하므로 디렉터리 목록에서 정확한 이름을 확인한다.
        String file = table + ".schema";
        String[] names = dir.toFile().list();
        if (names == null) throw new DbException("데이터 디렉터리를 읽을 수 없습니다: " + dir);
        if (!Arrays.asList(names).contains(file)) throw new DbException("테이블 \"" + table + "\"가 없습니다");
        try {
            Schema s = new Gson().fromJson(Files.readString(dir.resolve(file)), Schema.class);
            if (s == null || s.columns() == null) throw new DbException(file + " 형식 오류: columns가 없습니다");
            return s;
        } catch (JsonParseException e) {
            throw new DbException(file + " 형식 오류: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new DbException(e.toString(), e);
        }
    }

    /** CSV 파일이 아직 없으면 빈 테이블. */
    public static List<List<String>> readRows(Path dir, String table) throws DbException {
        Path f = dir.resolve(table + ".csv");
        if (!Files.exists(f)) return List.of();
        try {
            return parseCsv(Files.readString(f));
        } catch (IOException e) {
            throw new DbException(e.toString(), e);
        }
    }

    public static void appendRow(Path dir, String table, List<String> row) throws DbException {
        StringJoiner line = new StringJoiner(",", "", "\n");
        for (String v : row) line.add(needsQuotes(v) ? "\"" + v.replace("\"", "\"\"") + "\"" : v);
        try {
            Files.writeString(dir.resolve(table + ".csv"), line.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new DbException(e.toString(), e);
        }
    }

    private static boolean needsQuotes(String v) {
        return !v.isEmpty()
                && (v.chars().anyMatch(c -> c == ',' || c == '"' || c == '\r' || c == '\n')
                        || Character.isWhitespace(v.charAt(0)));
    }

    // RFC 4180 서브셋: 따옴표 필드, "" 이스케이프, 필드 안 줄바꿈, 빈 줄은 건너뜀.
    static List<List<String>> parseCsv(String s) throws DbException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean inQuotes = false;
        boolean any = false; // 현재 레코드에 내용이 있는가
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (inQuotes) {
                if (c != '"') {
                    f.append(c);
                } else if (i + 1 < n && s.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else {
                    inQuotes = false;
                }
            } else if (c == '"') {
                inQuotes = true;
                any = true;
            } else if (c == ',') {
                row.add(f.toString());
                f.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') i++;
                if (any || f.length() > 0) {
                    row.add(f.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                f.setLength(0);
                any = false;
            } else {
                f.append(c);
            }
        }
        if (inQuotes) throw new DbException("CSV 따옴표가 닫히지 않았습니다");
        if (any || f.length() > 0) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }
}
