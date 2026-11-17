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
                "SELECT FROM students;",
                "SELECT name, FROM students;",
                "SELECT * FROM students WHERE age 20;",
                "SELECT * FROM students WHERE age = ;",
                "SELECT * FROM students WHERE age = name;",
                "SELECT * FROM students WHERE age = 20 AND name = 'a';", // 조건은 1개만
                "SELECT * students;",
                "SELECT * FROM students", // ; 없음
                "DELETE FROM students;",
                "'oops")) {
            assertNotNull(Parser.parse(in).error(), in);
        }
    }

    @Test
    void parseSelectMixedWithInsert() {
        var r = Parser.parse("INSERT INTO students VALUES ('kim');\nselect * from students;");
        assertNull(r.error());
        assertEquals(List.of(
                new InsertStmt("students", List.of(new Token(STRING, "kim"))),
                new SelectStmt("students")), r.stmts());
    }

    @Test
    void parseSelectColumnsAndWhere() {
        var r = Parser.parse("SELECT name, Age FROM students WHERE age = 20;\nselect * from students where name = 'kim';");
        assertNull(r.error());
        assertEquals(List.of(
                new SelectStmt("students", List.of("name", "Age"), new SelectStmt.Where("age", new Token(NUMBER, "20"))),
                new SelectStmt("students", List.of("*"), new SelectStmt.Where("name", new Token(STRING, "kim")))), r.stmts());
    }

    @Test
    void parseStarAnywhereInColumnList() {
        var r = Parser.parse("SELECT name, * FROM t;\nSELECT *, *, age FROM t;");
        assertNull(r.error());
        assertEquals(List.of(
                new SelectStmt("t", List.of("name", "*"), null),
                new SelectStmt("t", List.of("*", "*", "age"), null)), r.stmts());
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
