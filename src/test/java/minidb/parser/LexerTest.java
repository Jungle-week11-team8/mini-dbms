package minidb.parser;

import static minidb.parser.Token.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LexerTest {
    @Test
    void lex() {
        var r = Lexer.lex("insert INTO students\n VALUES (42, 'O''Brien; x');");
        assertNull(r.error());
        assertEquals(List.of(
                new Token(KEYWORD, "INSERT"), new Token(KEYWORD, "INTO"), new Token(IDENT, "students"),
                new Token(KEYWORD, "VALUES"), new Token(SYMBOL, "("), new Token(NUMBER, "42"), new Token(SYMBOL, ","),
                new Token(STRING, "O'Brien; x"), new Token(SYMBOL, ")"), new Token(SYMBOL, ";")), r.tokens());
    }

    @Test
    void lexSelect() {
        var r = Lexer.lex("select * From students;");
        assertNull(r.error());
        assertEquals(List.of(
                new Token(KEYWORD, "SELECT"), new Token(SYMBOL, "*"), new Token(KEYWORD, "FROM"),
                new Token(IDENT, "students"), new Token(SYMBOL, ";")), r.tokens());
    }

    @Test
    void lexWhere() {
        var r = Lexer.lex("where age=20");
        assertNull(r.error());
        assertEquals(List.of(new Token(KEYWORD, "WHERE"), new Token(IDENT, "age"), new Token(SYMBOL, "="),
                new Token(NUMBER, "20")), r.tokens());
    }

    @Test
    void negativeNumber() {
        var r = Lexer.lex("-7");
        assertNull(r.error());
        assertEquals(List.of(new Token(NUMBER, "-7")), r.tokens());
    }

    @Test
    void comments() {
        var r = Lexer.lex("--insert\nselect /* 여러\n줄 */ * -- 줄 끝\nfrom t; /**/ -- 줄바꿈 없이 끝");
        assertNull(r.error());
        assertEquals(List.of(
                new Token(KEYWORD, "SELECT"), new Token(SYMBOL, "*"), new Token(KEYWORD, "FROM"),
                new Token(IDENT, "t"), new Token(SYMBOL, ";")), r.tokens());
    }

    @Test
    void commentOnly() {
        var r = Lexer.lex("-- a\n/* b */");
        assertNull(r.error());
        assertEquals(List.of(), r.tokens());
    }

    @Test
    void commentMarkersInsideStringAreKept() {
        var r = Lexer.lex("'a -- b /* c */' -7");
        assertNull(r.error());
        assertEquals(List.of(new Token(STRING, "a -- b /* c */"), new Token(NUMBER, "-7")), r.tokens());
    }

    @Test
    void blockCommentsDoNotNest() {
        var r = Lexer.lex("/* a /* b */ c */");
        assertEquals(List.of(new Token(IDENT, "c"), new Token(SYMBOL, "*")), r.tokens());
        assertEquals("알 수 없는 문자: '/'", r.error());
    }

    @Test
    void unterminatedBlockComment() {
        var r = Lexer.lex("select /* 안 닫힘");
        assertEquals(List.of(new Token(KEYWORD, "SELECT")), r.tokens());
        assertEquals("주석이 닫히지 않았습니다", r.error());
    }

    @Test
    void errors() {
        for (String in : List.of("'unterminated", "INSERT @", "-", "/", "//x", "/*")) {
            assertNotNull(Lexer.lex(in).error(), in);
        }
    }
}
