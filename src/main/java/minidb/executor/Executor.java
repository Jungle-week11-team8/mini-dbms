package minidb.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import minidb.DbException;
import minidb.index.BPlusTree;
import minidb.parser.InsertStmt;
import minidb.parser.SelectStmt;
import minidb.parser.Token;
import minidb.parser.Token.Kind;
import minidb.storage.Storage;
import minidb.storage.Storage.Column;

public final class Executor {
    private final Path dir;
    private final Map<String, Long> nextId = new HashMap<>(); // 테이블별 다음 _id, 처음 쓸 때 CSV를 스캔해 채움

    // 테이블별 _id 인덱스(_id → CSV 바이트 오프셋). 그 테이블에 처음 INSERT할 때 만든다.
    // 지금은 이번 실행에서 INSERT한 행만 들어 있고, 아직 조회에 쓰이지 않는다.
    private final Map<String, BPlusTree> indexes = new HashMap<>();

    /** 인덱스 노드당 최대 키 개수. 벤치마크 전까지의 임시값. */
    private static final int INDEX_MAX_KEYS = 128;

    /** 모든 테이블에 붙는 시스템 컬럼. CSV의 첫 필드이고 schema에는 적지 않는다. */
    public static final Column SYSTEM_ID = new Column("_id", "INT");

    public Executor(Path dir) {
        this.dir = dir;
    }

    /** _id는 자동 부여하고, VALUES에는 schema의 사용자 컬럼만 순서대로 받는다. */
    public void insert(InsertStmt s) throws DbException {
        List<Column> cols = userColumns(s.table());
        if (s.values().size() != cols.size()) {
            throw new DbException(s.table() + ": 값 " + cols.size() + "개가 필요한데 " + s.values().size() + "개가 왔습니다");
        }

        List<String> row = new ArrayList<>();
        long id = takeId(s.table());
        row.add(String.valueOf(id));
        for (int i = 0; i < cols.size(); i++) {
            Token v = s.values().get(i);
            checkType(s.table(), cols.get(i), v);
            row.add(v.value());
        }
        // CSV에 먼저 쓰고, 성공했을 때만 인덱스에 등록한다(쓰기가 실패하면 트리는 그대로).
        long offset = Storage.appendRow(dir, s.table(), row);
        indexes.computeIfAbsent(s.table(), t -> new BPlusTree(INDEX_MAX_KEYS)).insert(id, offset);
    }

    /** SELECT의 결과: schema 순서의 컬럼 이름과 CSV의 모든 행. */
    public record QueryResult(List<String> columns, List<List<String>> rows) {}

    /** 컬럼 이름은 대소문자 무시, 결과 헤더는 schema 표기. 조건에 맞는 행이 없으면 빈 결과(오류 아님). */
    public QueryResult select(SelectStmt s) throws DbException {
        List<Column> all = new ArrayList<>(); // CSV 필드 순서와 같음: _id, 사용자 컬럼들
        all.add(SYSTEM_ID);
        all.addAll(userColumns(s.table()));
        List<Integer> picked = new ArrayList<>();
        for (String name : s.columns()) {
            if (name.equals("*")) {
                for (int i = 0; i < all.size(); i++) picked.add(i);
            } else {
                picked.add(columnIndex(s.table(), all, name));
            }
        }

        int whereIdx = -1;
        if (s.where() != null) {
            whereIdx = columnIndex(s.table(), all, s.where().column());
            checkType(s.table(), all.get(whereIdx), s.where().value());
        }

        List<List<String>> rows = new ArrayList<>();
        for (List<String> r : Storage.readRows(dir, s.table())) {
            if (whereIdx >= 0 && !matches(all.get(whereIdx), r.get(whereIdx), s.where().value())) continue;
            List<String> out = new ArrayList<>();
            for (int i : picked) out.add(r.get(i));
            rows.add(out);
        }
        List<String> header = picked.stream().map(i -> all.get(i).name()).toList();
        return new QueryResult(header, rows);
    }

    /** schema의 컬럼(시스템 _id 제외). 없는 테이블이거나 _id와 이름이 겹치면 오류. */
    private List<Column> userColumns(String table) throws DbException {
        List<Column> cols = Storage.loadSchema(dir, table).columns();
        for (Column c : cols) {
            if (c.name().equalsIgnoreCase(SYSTEM_ID.name())) {
                throw new DbException(table + ".schema: \"" + c.name() + "\"는 시스템 컬럼 이름이라 쓸 수 없습니다");
            }
        }
        return cols;
    }

    private static int columnIndex(String table, List<Column> cols, String name) throws DbException {
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).name().equalsIgnoreCase(name)) return i;
        }
        throw new DbException(table + ": 컬럼 \"" + name + "\"가 없습니다");
    }

    /** INT는 숫자로 비교(020 = 20), TEXT는 그대로 비교. */
    private static boolean matches(Column c, String stored, Token v) throws DbException {
        if (!"INT".equals(c.type())) return stored.equals(v.value());
        try {
            return Long.parseLong(stored) == Long.parseLong(v.value());
        } catch (NumberFormatException e) {
            throw new DbException("CSV의 " + c.name() + " 값이 정수가 아닙니다: \"" + stored + "\"");
        }
    }

    /** INSERT 값과 WHERE 값 공통 규칙: INT에는 숫자, TEXT에는 '문자열'만. */
    private static void checkType(String table, Column c, Token v) throws DbException {
        boolean isInt = "INT".equals(c.type());
        boolean isText = "TEXT".equals(c.type());
        if (isInt && v.kind() == Kind.NUMBER) {
            try {
                Long.parseLong(v.value());
            } catch (NumberFormatException e) {
                throw new DbException(table + "." + c.name() + ": 정수 범위를 벗어났습니다: " + v.value());
            }
        } else if (!(isText && v.kind() == Kind.STRING)) {
            if (!isInt && !isText) {
                throw new DbException(table + ".schema: 알 수 없는 타입 \"" + c.type() + "\"");
            }
            throw new DbException(table + "." + c.name() + ": " + c.type() + " 타입에 맞지 않는 값 \"" + v.value() + "\"");
        }
    }

    /** id를 하나 예약한다. appendRow가 실패하면 그 id는 건너뛰어진다(중복은 없음). */
    private long takeId(String table) throws DbException {
        Long cached = nextId.get(table);
        long id;
        if (cached != null) {
            id = cached;
        } else {
            long max = 0;
            for (List<String> r : Storage.readRows(dir, table)) {
                try {
                    max = Math.max(max, Long.parseLong(r.get(0)));
                } catch (NumberFormatException e) {
                    throw new DbException(table + ".csv: 잘못된 id \"" + r.get(0) + "\"");
                }
            }
            id = max + 1;
        }
        nextId.put(table, id + 1);
        return id;
    }
}
