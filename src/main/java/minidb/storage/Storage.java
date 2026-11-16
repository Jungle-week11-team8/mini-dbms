package minidb.storage;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
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

    /** 행을 파일 끝에 덧붙이고, 그 행이 시작하는 바이트 오프셋(쓰기 직전의 파일 크기)을 돌려준다. */
    public static long appendRow(Path dir, String table, List<String> row) throws DbException {
        StringJoiner line = new StringJoiner(",", "", "\n");
        for (String v : row) line.add(needsQuotes(v) ? "\"" + v.replace("\"", "\"\"") + "\"" : v);
        Path f = dir.resolve(table + ".csv");
        try {
            long offset = Files.exists(f) ? Files.size(f) : 0;
            Files.writeString(f, line.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return offset;
        } catch (IOException e) {
            throw new DbException(e.toString(), e);
        }
    }

    /** scanIds가 행마다 부르는 콜백: 그 행의 _id와 시작 바이트 오프셋. */
    public interface IdVisitor {
        void visit(long id, long offset) throws DbException;
    }

    /**
     * CSV를 바이트 단위로 한 번 훑어 각 행의 _id(첫 필드)와 시작 오프셋을 알려준다. 파일이 없으면 아무것도 하지 않는다.
     * 행 경계 규칙은 parseCsv와 같다(따옴표 안 줄바꿈은 행의 끝이 아님, 빈 줄은 건너뜀).
     * UTF-8의 여러 바이트 글자는 따옴표·쉼표·줄바꿈 바이트를 포함하지 않으므로 바이트만 봐도 안전하다.
     */
    public static void scanIds(Path dir, String table, IdVisitor visitor) throws DbException {
        Path f = dir.resolve(table + ".csv");
        if (!Files.exists(f)) return;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f), 1 << 16)) {
            ByteArrayOutputStream first = new ByteArrayOutputStream(); // 현재 행의 첫 필드
            long pos = 0; // 지금 읽는 바이트의 위치
            long rowStart = 0;
            boolean inQuotes = false;
            boolean quotePending = false; // 따옴표 안에서 "를 봄: 다음이 "면 이스케이프, 아니면 닫는 따옴표
            boolean any = false; // 현재 행에 내용이 있는가
            boolean firstDone = false; // 첫 필드가 끝났는가
            boolean afterCr = false;
            for (int c; (c = in.read()) >= 0; pos++) {
                if (quotePending) {
                    quotePending = false;
                    if (c == '"') {
                        if (!firstDone) first.write(c);
                        continue;
                    }
                    inQuotes = false; // 닫는 따옴표였다. 이 바이트는 아래에서 따옴표 밖 문자로 처리한다.
                }
                if (inQuotes) {
                    if (c == '"') quotePending = true;
                    else if (!firstDone) first.write(c);
                    continue;
                }
                if (c == '\n' && afterCr) { // \r\n의 \n
                    afterCr = false;
                    rowStart = pos + 1;
                    continue;
                }
                afterCr = false;
                if (c == '\n' || c == '\r') {
                    if (any) visitor.visit(parseId(table, first), rowStart);
                    first.reset();
                    any = false;
                    firstDone = false;
                    afterCr = c == '\r';
                    rowStart = pos + 1;
                } else {
                    any = true;
                    if (c == '"') inQuotes = true;
                    else if (c == ',') firstDone = true;
                    else if (!firstDone) first.write(c);
                }
            }
            if (inQuotes && !quotePending) throw new DbException("CSV 따옴표가 닫히지 않았습니다");
            if (any) visitor.visit(parseId(table, first), rowStart);
        } catch (IOException e) {
            throw new DbException(e.toString(), e);
        }
    }

    private static long parseId(String table, ByteArrayOutputStream field) throws DbException {
        String s = field.toString(StandardCharsets.UTF_8);
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new DbException(table + ".csv: 잘못된 id \"" + s + "\"");
        }
    }

    /** offset에서 시작하는 한 행만 읽는다. offset은 appendRow나 scanIds가 알려준 값이어야 한다. */
    public static List<String> readRowAt(Path dir, String table, long offset) throws DbException {
        ByteArrayOutputStream row = new ByteArrayOutputStream();
        try (RandomAccessFile f = new RandomAccessFile(dir.resolve(table + ".csv").toFile(), "r")) {
            f.seek(offset);
            byte[] buf = new byte[4096];
            boolean inQuotes = false; // ""는 두 번 뒤집혀 제자리이므로 행의 끝을 찾는 데는 뒤집기만으로 충분하다
            boolean done = false;
            for (int n; !done && (n = f.read(buf)) > 0; ) {
                int end = 0;
                for (; end < n; end++) {
                    byte c = buf[end];
                    if (c == '"') {
                        inQuotes = !inQuotes;
                    } else if (!inQuotes && (c == '\n' || c == '\r')) {
                        done = true;
                        break;
                    }
                }
                row.write(buf, 0, end);
            }
        } catch (IOException e) {
            throw new DbException(e.toString(), e);
        }
        List<List<String>> rows = parseCsv(row.toString(StandardCharsets.UTF_8));
        if (rows.isEmpty()) throw new DbException(table + ".csv: 오프셋 " + offset + "에 행이 없습니다");
        return rows.get(0);
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
