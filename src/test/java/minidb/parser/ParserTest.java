package minidb.parser;

import static minidb.parser.Token.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParserTest {
    @Test
    void parseInserts() {
        var r = Parser.parse("INSERT INTO students VALUES ('kim', 20);\ninsert into Students values ('lee');");
        assertNull(r.error());
        assertEquals(List.of(
                new InsertStmt("students", List.of(new Token(STRING, "kim"), new Token(NUMBER, "20"))),
                new InsertStmt("Students", List.of(new Token(STRING, "lee")))), r.stmts());
    }

    @Test
    void parseErrors() {
        for (String in : List.of(
                "INSERT INTO students VALUES ('kim')", // ; 없음
                "INSERT students VALUES ('kim');",
                "INSERT INTO students VALUES ();",
                "INSERT INTO students VALUES ('a' 'b');",
                "SELECT * FROM students;",
                "'oops")) {
            assertNotNull(Parser.parse(in).error(), in);
        }
    }

    @Test
    void returnsStatementsBeforeError() {
        var r = Parser.parse("INSERT INTO a VALUES (1);\nINSERT INTO b VALUES (2);\nINSERT b;");
        assertNotNull(r.error());
        assertEquals(2, r.stmts().size());
    }

    @Test
    void lexErrorKeepsEarlierStatements() {
        var r = Parser.parse("INSERT INTO a VALUES (1);\nINSERT INTO a VALUES ('oops);");
        assertNotNull(r.error());
        assertEquals(1, r.stmts().size());
    }

    @Test
    void empty() {
        var r = Parser.parse("  \n");
        assertNull(r.error());
        assertTrue(r.stmts().isEmpty());
    }
}
