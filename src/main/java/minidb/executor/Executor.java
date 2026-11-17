package minidb.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import minidb.DbException;
import minidb.parser.InsertStmt;
import minidb.parser.SelectStmt;
import minidb.parser.Token;
import minidb.parser.Token.Kind;
import minidb.storage.Storage;
import minidb.storage.Storage.Column;

/**
 * 여러 스레드가 인스턴스 하나를 공유해도 된다. 같은 테이블의 INSERT/SELECT는 테이블별 배타 락(TableState 동기화)으로
 * 한 번에 하나씩 실행되고, 다른 테이블끼리는 병렬로 실행된다.
 */
public final class Executor {
    private final Path dir;
    // schema가 있는 테이블만 들어간다(없는 테이블 이름으로 항목이 쌓이지 않도록).
    private final ConcurrentHashMap<String, TableState> tables = new ConcurrentHashMap<>();

    /** 모든 테이블에 붙는 시스템 컬럼. CSV의 첫 필드이고 schema에는 적지 않는다. */
    public static final Column SYSTEM_ID = new Column("_id", "INT");

    public Executor(Path dir) {
        this.dir = dir;
    }

    /** _id는 자동 부여하고, VALUES에는 schema의 사용자 컬럼만 순서대로 받는다. */
    public void insert(InsertStmt s) throws DbException {
        List<Column> cols = loadUserColumns(s.table());
        if (s.values().size() != cols.size()) {
            throw new DbException(s.table() + ": 값 " + cols.size() + "개가 필요한데 " + s.values().size() + "개가 왔습니다");
        }
        for (int i = 0; i < cols.size(); i++) checkType(s.table(), cols.get(i), s.values().get(i));

        TableState table = getOrCreateTable(s.table());
        synchronized (table) { // 파일 끝 오프셋 읽기~쓰기, id 예약, 인덱스 등록을 한 덩어리로
            long id = table.reserveNextId();
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(id));
            for (Token v : s.values()) row.add(v.value());
            // CSV에 먼저 쓰고, 성공했을 때만 인덱스에 등록한다(쓰기가 실패하면 트리는 그대로).
            long offset = Storage.appendRow(dir, s.table(), row);
            table.ensureIndexLoaded().insert(id, offset);
        }
    }

    /** SELECT의 결과: schema 순서의 컬럼 이름과 CSV의 모든 행. */
    public record QueryResult(List<String> columns, List<List<String>> rows) {}

    /** 컬럼 이름은 대소문자 무시, 결과 헤더는 schema 표기. 조건에 맞는 행이 없으면 빈 결과(오류 아님). */
    public QueryResult select(SelectStmt s) throws DbException {
        List<Column> all = new ArrayList<>(); // CSV 필드 순서와 같음: _id, 사용자 컬럼들
        all.add(SYSTEM_ID);
        all.addAll(loadUserColumns(s.table()));
        List<Integer> picked = new ArrayList<>();
        for (String name : s.columns()) {
            if (name.equals("*")) {
                for (int i = 0; i < all.size(); i++) picked.add(i);
            } else {
                picked.add(findColumnIndex(s.table(), all, name));
            }
        }

        int whereIdx = -1;
        if (s.where() != null) {
            whereIdx = findColumnIndex(s.table(), all, s.where().column());
            checkType(s.table(), all.get(whereIdx), s.where().value());
        }

        List<List<String>> rows = new ArrayList<>();
        TableState table = getOrCreateTable(s.table());
        synchronized (table) { // 읽는 도중 INSERT가 끼어들어 반쯤 쓴 행을 보거나, 인덱스를 두 번 만들지 않도록
            if (whereIdx == 0) { // WHERE _id = n: 인덱스로 그 행 하나만 읽는다
                List<String> r = findRowById(table, s.table(), Long.parseLong(s.where().value().value()));
                if (r != null) rows.add(pickColumns(r, picked));
            } else { // 그 외에는 CSV 전체를 훑는다
                for (List<String> r : Storage.readRows(dir, s.table())) {
                    if (whereIdx >= 0 && !matches(all.get(whereIdx), r.get(whereIdx), s.where().value())) continue;
                    rows.add(pickColumns(r, picked));
                }
            }
        }
        List<String> header = picked.stream().map(i -> all.get(i).name()).toList();
        return new QueryResult(header, rows);
    }

    /** 테이블의 메모리 상태. schema 확인(loadUserColumns)이 성공한 뒤에만 부른다. */
    private TableState getOrCreateTable(String table) {
        return tables.computeIfAbsent(table, name -> new TableState(dir, name));
    }

    private static List<String> pickColumns(List<String> row, List<Integer> picked) {
        List<String> out = new ArrayList<>();
        for (int i : picked) out.add(row.get(i));
        return out;
    }

    /** 인덱스로 찾은 행, 없으면 null. 읽은 행의 _id가 찾던 값이 아니면 오류(실행 중 CSV가 바뀐 경우 등). */
    private List<String> findRowById(TableState state, String table, long id) throws DbException {
        OptionalLong offset = state.ensureIndexLoaded().search(id);
        if (offset.isEmpty()) return null;
        List<String> row = Storage.readRowAt(dir, table, offset.getAsLong());
        boolean same;
        try {
            same = Long.parseLong(row.get(0)) == id;
        } catch (NumberFormatException e) {
            same = false;
        }
        if (!same) {
            throw new DbException(table + ".csv: 인덱스가 가리킨 위치의 행이 _id " + id + "가 아닙니다(실행 중 파일이 바뀌었을 수 있습니다)");
        }
        return row;
    }

    /** schema의 컬럼(시스템 _id 제외). 없는 테이블이거나 _id와 이름이 겹치면 오류. */
    private List<Column> loadUserColumns(String table) throws DbException {
        List<Column> cols = Storage.loadSchema(dir, table).columns();
        for (Column c : cols) {
            if (c.name().equalsIgnoreCase(SYSTEM_ID.name())) {
                throw new DbException(table + ".schema: \"" + c.name() + "\"는 시스템 컬럼 이름이라 쓸 수 없습니다");
            }
        }
        return cols;
    }

    private static int findColumnIndex(String table, List<Column> cols, String name) throws DbException {
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
}
