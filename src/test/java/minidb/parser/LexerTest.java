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
    void errors() {
        for (String in : List.of("'unterminated", "INSERT @", "-")) {
            assertNotNull(Lexer.lex(in).error(), in);
        }
    }
}
