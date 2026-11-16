package minidb.executor;

import static minidb.parser.Token.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import minidb.DbException;
import minidb.parser.InsertStmt;
import minidb.parser.SelectStmt;
import minidb.parser.Token;
import minidb.storage.Storage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutorTest {
    static final Token KIM = new Token(STRING, "kim");
    static final Token LEE = new Token(STRING, "lee");
    static final Token N20 = new Token(NUMBER, "20");

    @TempDir Path dir;

    @BeforeEach
    void setup() throws Exception {
        Files.writeString(dir.resolve("students.schema"),
                "{\"columns\":[{\"name\":\"name\",\"type\":\"TEXT\"},{\"name\":\"age\",\"type\":\"INT\"}]}");
    }

    static InsertStmt ins(String table, Token... vals) {
        return new InsertStmt(table, List.of(vals));
    }

    @Test
    void assignsIdFromExistingMax() throws DbException {
        Storage.appendRow(dir, "students", List.of("7", "old", "30"));
        var e = new Executor(dir);
        e.insert(ins("students", KIM, N20));
        e.insert(ins("students", LEE, N20));
        assertEquals(List.of(List.of("7", "old", "30"), List.of("8", "kim", "20"), List.of("9", "lee", "20")),
                Storage.readRows(dir, "students"));
    }

    @Test
    void insertErrors() throws DbException {
        Map<String, InsertStmt> cases = Map.of(
                "없는 테이블", ins("nope", KIM, N20),
                "테이블명 대소문자", ins("Students", KIM, N20),
                "_id 직접 입력(값 초과)", ins("students", N20, KIM, N20),
                "값 부족", ins("students", KIM),
                "INT 자리에 문자열", ins("students", KIM, KIM),
                "TEXT 자리에 숫자", ins("students", N20, N20));
        cases.forEach((name, s) -> assertThrows(DbException.class, () -> new Executor(dir).insert(s), name));
        assertTrue(Storage.readRows(dir, "students").isEmpty(), "실패한 INSERT가 저장됨");
    }

    @Test
    void selectReturnsSchemaColumnsAndAllRows() throws DbException {
        var e = new Executor(dir);
        e.insert(ins("students", KIM, N20));
        e.insert(ins("students", LEE, N20));
        var r = e.select(new SelectStmt("students"));
        assertEquals(List.of("_id", "name", "age"), r.columns());
        assertEquals(List.of(List.of("1", "kim", "20"), List.of("2", "lee", "20")), r.rows());
    }

    @Test
    void selectEmptyTable() throws DbException {
        var r = new Executor(dir).select(new SelectStmt("students"));
        assertEquals(List.of("_id", "name", "age"), r.columns());
        assertTrue(r.rows().isEmpty());
    }

    @Test
    void selectMissingTable() {
        assertThrows(DbException.class, () -> new Executor(dir).select(new SelectStmt("nope")));
        assertThrows(DbException.class, () -> new Executor(dir).select(new SelectStmt("Students")));
    }

    static SelectStmt sel(List<String> cols, String whereCol, Token whereVal) {
        return new SelectStmt("students", cols, whereCol == null ? null : new SelectStmt.Where(whereCol, whereVal));
    }

    void seed() throws DbException {
        var e = new Executor(dir);
        e.insert(ins("students", KIM, N20));
        e.insert(ins("students", LEE, new Token(NUMBER, "21")));
        e.insert(ins("students", KIM, new Token(NUMBER, "22")));
    }

    @Test
    void selectColumnsInRequestedOrderIgnoringCase() throws DbException {
        seed();
        var r = new Executor(dir).select(sel(List.of("AGE", "Name"), null, null));
        assertEquals(List.of("age", "name"), r.columns()); // 헤더는 schema 표기
        assertEquals(List.of(List.of("20", "kim"), List.of("21", "lee"), List.of("22", "kim")), r.rows());
    }

    @Test
    void selectWhereText() throws DbException {
        seed();
        var r = new Executor(dir).select(sel(List.of("_ID"), "NAME", KIM));
        assertEquals(List.of(List.of("1"), List.of("3")), r.rows());
    }

    @Test
    void selectWhereIntComparesAsNumber() throws DbException {
        seed();
        var r = new Executor(dir).select(sel(List.of("*"), "age", new Token(NUMBER, "021")));
        assertEquals(List.of(List.of("2", "lee", "21")), r.rows());
    }

    @Test
    void selectWhereNoMatchIsEmptyNotError() throws DbException {
        seed();
        var r = new Executor(dir).select(sel(List.of("*"), "_id", new Token(NUMBER, "99")));
        assertEquals(List.of("_id", "name", "age"), r.columns());
        assertTrue(r.rows().isEmpty());
    }

    @Test
    void selectErrors() throws DbException {
        seed();
        Map<String, SelectStmt> cases = Map.of(
                "없는 컬럼", sel(List.of("nope"), null, null),
                "WHERE 없는 컬럼", sel(List.of("*"), "nope", KIM),
                "INT 컬럼에 문자열", sel(List.of("*"), "age", new Token(STRING, "20")),
                "TEXT 컬럼에 숫자", sel(List.of("*"), "name", N20));
        cases.forEach((name, s) -> assertThrows(DbException.class, () -> new Executor(dir).select(s), name));
    }

    @Test
    void starExpandsInPlace() throws DbException {
        seed();
        var r = new Executor(dir).select(sel(List.of("name", "*"), "_id", new Token(NUMBER, "2")));
        assertEquals(List.of("name", "_id", "name", "age"), r.columns());
        assertEquals(List.of(List.of("lee", "2", "lee", "21")), r.rows());
    }

    @Test
    void schemaColumnNamedIdIsOrdinaryColumn() throws Exception {
        Files.writeString(dir.resolve("t.schema"),
                "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"name\",\"type\":\"TEXT\"}]}");
        var e = new Executor(dir);
        e.insert(new InsertStmt("t", List.of(new Token(NUMBER, "100"), KIM))); // id는 사용자가 넣는 일반 컬럼
        var r = e.select(new SelectStmt("t"));
        assertEquals(List.of("_id", "id", "name"), r.columns());
        assertEquals(List.of(List.of("1", "100", "kim")), r.rows());
    }

    @Test
    void schemaWithSystemColumnNameIsError() throws Exception {
        Files.writeString(dir.resolve("bad.schema"),
                "{\"columns\":[{\"name\":\"_ID\",\"type\":\"INT\"},{\"name\":\"name\",\"type\":\"TEXT\"}]}");
        var e = new Executor(dir);
        assertThrows(DbException.class, () -> e.insert(new InsertStmt("bad", List.of(N20, KIM))));
        assertThrows(DbException.class, () -> e.select(new SelectStmt("bad")));
    }

    static SelectStmt byId(long id) {
        return sel(List.of("*"), "_id", new Token(NUMBER, String.valueOf(id)));
    }

    /** 실행 전부터 CSV에 있던 행(재구축)과 이번 실행에서 넣은 행(INSERT 등록) 모두 _id로 찾는다. */
    @Test
    void selectByIdFindsExistingAndNewRows() throws DbException {
        Storage.appendRow(dir, "students", List.of("1", "김철수", "30"));
        Storage.appendRow(dir, "students", List.of("5", "줄\n바꿈, \"따옴표\"", "31"));
        Storage.appendRow(dir, "students", List.of("3", "old", "32"));
        var e = new Executor(dir);
        assertEquals(List.of(List.of("5", "줄\n바꿈, \"따옴표\"", "31")), e.select(byId(5)).rows());
        assertEquals(List.of(List.of("1", "김철수", "30")), e.select(byId(1)).rows());
        assertEquals(List.of(List.of("3", "old", "32")), e.select(byId(3)).rows());
        assertTrue(e.select(byId(2)).rows().isEmpty());

        e.insert(ins("students", new Token(STRING, "이영희"), N20)); // 조회로 인덱스를 만든 뒤의 INSERT도 최대 _id 다음 번호
        e.insert(ins("students", LEE, N20));
        assertEquals(List.of(List.of("6", "이영희", "20")), e.select(byId(6)).rows());
        assertEquals(List.of(List.of("7", "lee", "20")), e.select(byId(7)).rows());
        assertEquals(List.of(List.of("3", "old", "32")), e.select(byId(3)).rows());
        assertEquals(List.of(List.of("lee")), e.select(sel(List.of("name"), "_ID", new Token(NUMBER, "007"))).rows());
    }

    /** 인덱스를 타는 증거: 다른 행의 _id를 망가뜨리면 전체를 훑는 조회는 실패하지만 _id 조회는 그 행만 읽으므로 성공한다. */
    @Test
    void selectByIdReadsOnlyThatRow() throws Exception {
        seed();
        var e = new Executor(dir);
        assertEquals(List.of(List.of("2", "lee", "21")), e.select(byId(2)).rows()); // 여기서 인덱스가 만들어진다
        Files.writeString(dir.resolve("students.csv"), "x,kim,20\n2,lee,21\n3,kim,22\n"); // 길이는 그대로, 1번 행의 _id만 깨뜨림

        assertEquals(List.of(List.of("2", "lee", "21")), e.select(byId(2)).rows());
        assertEquals(List.of(List.of("3", "kim", "22")), e.select(byId(3)).rows());
        assertThrows(DbException.class, () -> new Executor(dir).select(byId(2)), "새로 훑으면 깨진 행에서 실패해야 함");
    }

    @Test
    void duplicateIdInCsvIsError() throws DbException {
        Storage.appendRow(dir, "students", List.of("1", "a", "20"));
        Storage.appendRow(dir, "students", List.of("2", "b", "20"));
        Storage.appendRow(dir, "students", List.of("1", "c", "20"));
        var ex = assertThrows(DbException.class, () -> new Executor(dir).select(byId(2)));
        assertTrue(ex.getMessage().contains("중복"), ex.getMessage());
        assertThrows(DbException.class, () -> new Executor(dir).insert(ins("students", KIM, N20)));
        assertEquals(3, Storage.readRows(dir, "students").size(), "실패한 INSERT가 저장됨");
    }

    /** 실행 중 CSV가 바뀌어 오프셋이 다른 행을 가리키면 엉뚱한 행을 돌려주지 않고 오류. */
    @Test
    void rowAtOffsetWithDifferentIdIsError() throws Exception {
        seed();
        var e = new Executor(dir);
        e.select(byId(1));
        Files.writeString(dir.resolve("students.csv"), "9,kim,20\n2,lee,21\n3,kim,22\n");
        assertThrows(DbException.class, () -> e.select(byId(1)));
    }
}
