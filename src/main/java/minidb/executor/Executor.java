package minidb.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import minidb.DbException;
import minidb.parser.InsertStmt;
import minidb.parser.SelectStmt;
import minidb.parser.Token;
import minidb.parser.Token.Kind;
import minidb.storage.Storage;
import minidb.storage.Storage.Column;
import minidb.storage.Storage.Schema;

public final class Executor {
    private final Path dir;
    private final Map<String, Long> nextId = new HashMap<>(); // 테이블별 다음 id, 처음 쓸 때 CSV를 스캔해 채움

    public Executor(Path dir) {
        this.dir = dir;
    }

    /** schema 첫 컬럼이 "id INT"이면 id를 자동 부여하고, VALUES에는 나머지 컬럼만 받는다. */
    public void insert(InsertStmt s) throws DbException {
        Schema schema = Storage.loadSchema(dir, s.table());
        List<Column> cols = schema.columns();
        boolean autoId = !cols.isEmpty() && cols.get(0).equals(new Column("id", "INT"));
        if (autoId) cols = cols.subList(1, cols.size());
        if (s.values().size() != cols.size()) {
            throw new DbException(s.table() + ": 값 " + cols.size() + "개가 필요한데 " + s.values().size() + "개가 왔습니다");
        }

        List<String> row = new ArrayList<>();
        if (autoId) row.add(String.valueOf(takeId(s.table())));
        for (int i = 0; i < cols.size(); i++) {
            Column c = cols.get(i);
            Token v = s.values().get(i);
            boolean isInt = "INT".equals(c.type());
            boolean isText = "TEXT".equals(c.type());
            if (isInt && v.kind() == Kind.NUMBER) {
                try {
                    Long.parseLong(v.value());
                } catch (NumberFormatException e) {
                    throw new DbException(s.table() + "." + c.name() + ": 정수 범위를 벗어났습니다: " + v.value());
                }
            } else if (!(isText && v.kind() == Kind.STRING)) {
                if (!isInt && !isText) {
                    throw new DbException(s.table() + ".schema: 알 수 없는 타입 \"" + c.type() + "\"");
                }
                throw new DbException(s.table() + "." + c.name() + ": " + c.type() + " 타입에 맞지 않는 값 \"" + v.value() + "\"");
            }
            row.add(v.value());
        }
        Storage.appendRow(dir, s.table(), row);
    }

    /** SELECT의 결과: schema 순서의 컬럼 이름과 CSV의 모든 행. */
    public record QueryResult(List<String> columns, List<List<String>> rows) {}

    public QueryResult select(SelectStmt s) throws DbException {
        Schema schema = Storage.loadSchema(dir, s.table()); // 없는 테이블이면 여기서 오류
        List<String> columns = schema.columns().stream().map(Column::name).toList();
        return new QueryResult(columns, Storage.readRows(dir, s.table()));
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
