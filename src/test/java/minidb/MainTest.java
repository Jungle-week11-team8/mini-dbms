package minidb;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @TempDir Path dir;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream errOut = new ByteArrayOutputStream();

    Path data() {
        return dir.resolve("data");
    }

    /** data/students.schema와 q.sql을 만들고 run을 실행해 종료 코드를 돌려준다. */
    int runSql(String sql) throws Exception {
        Files.createDirectories(data());
        Files.writeString(data().resolve("students.schema"),
                "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"name\",\"type\":\"TEXT\"}]}");
        Path q = dir.resolve("q.sql");
        Files.writeString(q, sql);
        return Main.run(new String[] {q.toString()}, data(), new PrintStream(out, true, UTF_8), new PrintStream(errOut, true, UTF_8));
    }

    String csv() throws Exception {
        return Files.readString(data().resolve("students.csv"));
    }

    @Test
    void runInserts() throws Exception {
        int code = runSql("INSERT INTO students VALUES ('kim');\ninsert into students values ('O''Brien, Jr');\n");
        assertEquals(0, code, errOut.toString(UTF_8));
        assertEquals("INSERT 1\nINSERT 1\n", out.toString(UTF_8).replace("\r\n", "\n"));
        assertEquals("1,kim\n2,\"O'Brien, Jr\"\n", csv());
    }

    @Test
    void stopsAtFirstError() throws Exception {
        int code = runSql("INSERT INTO students VALUES ('a');\nINSERT INTO nope VALUES ('b');\nINSERT INTO students VALUES ('c');");
        assertEquals(1, code);
        assertTrue(errOut.toString(UTF_8).contains("2번째 문장"));
        assertEquals("1,a\n", csv());
    }

    @Test
    void syntaxErrorAfterValidStatement() throws Exception {
        assertEquals(1, runSql("INSERT INTO students VALUES ('a');\nINSERT INTO students ('b');"));
        assertEquals("1,a\n", csv());
    }

    @Test
    void selectPrintsTable() throws Exception {
        int code = runSql("INSERT INTO students VALUES ('kim');\nINSERT INTO students VALUES ('O''Brien');\nSELECT * FROM students;");
        assertEquals(0, code, errOut.toString(UTF_8));
        assertEquals("""
                INSERT 1
                INSERT 1
                +----+---------+
                | id | name    |
                +----+---------+
                | 1  | kim     |
                | 2  | O'Brien |
                +----+---------+
                2 rows
                """, out.toString(UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void selectEmptyTablePrintsHeaderAndZeroRows() throws Exception {
        assertEquals(0, runSql("select * from students;"), errOut.toString(UTF_8));
        assertEquals("""
                +----+------+
                | id | name |
                +----+------+
                0 rows
                """, out.toString(UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void selectMissingTableFails() throws Exception {
        assertEquals(1, runSql("SELECT * FROM nope;"));
        assertTrue(errOut.toString(UTF_8).contains("1번째 문장"));
    }

    @Test
    void usageErrors() {
        var o = new PrintStream(out, true, UTF_8);
        assertEquals(1, Main.run(new String[0], data(), o, o));
        assertEquals(1, Main.run(new String[] {dir.resolve("missing.sql").toString()}, data(), o, o));
    }
}
