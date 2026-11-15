package minidb.executor;

import static minidb.parser.Token.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import minidb.DbException;
import minidb.parser.InsertStmt;
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
                "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"name\",\"type\":\"TEXT\"},{\"name\":\"age\",\"type\":\"INT\"}]}");
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
                "id 직접 입력", ins("students", N20, KIM, N20),
                "값 부족", ins("students", KIM),
                "INT 자리에 문자열", ins("students", KIM, KIM),
                "TEXT 자리에 숫자", ins("students", N20, N20));
        cases.forEach((name, s) -> assertThrows(DbException.class, () -> new Executor(dir).insert(s), name));
        assertTrue(Storage.readRows(dir, "students").isEmpty(), "실패한 INSERT가 저장됨");
    }
}
