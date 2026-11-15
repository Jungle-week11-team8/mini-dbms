package minidb.parser;

import java.util.ArrayList;
import java.util.List;
import minidb.parser.Token.Kind;

public final class Parser {
    /**
     * 문법 오류가 나면 그 앞까지 파싱된 문장과 오류를 함께 돌려준다
     * (앞 문장은 실행하고 오류 지점에서 멈추기 위해). error는 오류가 없으면 null.
     */
    public record Result(List<InsertStmt> stmts, String error) {}

    private static final class SyntaxError extends Exception {
        SyntaxError(String message) {
            super(message);
        }
    }

    private final List<Token> toks;
    private int pos;

    private Parser(List<Token> toks) {
        this.toks = toks;
    }

    /** 토큰화 오류도 같은 방식: 오류 전까지 나온 토큰으로 완성된 문장만 돌려준다. */
    public static Result parse(String src) {
        Lexer.Result lex = Lexer.lex(src);
        Parser p = new Parser(lex.tokens());
        List<InsertStmt> stmts = new ArrayList<>();
        while (p.pos < p.toks.size()) {
            try {
                stmts.add(p.insert());
            } catch (SyntaxError e) {
                // 잘린 문장의 원인은 토큰화 오류
                String msg = lex.error() != null && p.pos >= p.toks.size() ? lex.error() : e.getMessage();
                return new Result(stmts, (stmts.size() + 1) + "번째 문장: " + msg);
            }
        }
        if (lex.error() != null) return new Result(stmts, (stmts.size() + 1) + "번째 문장: " + lex.error());
        return new Result(stmts, null);
    }

    private Token next() {
        return pos < toks.size() ? toks.get(pos++) : null;
    }

    private static String q(String s) {
        return "\"" + s + "\"";
    }

    private void expect(Kind kind, String value) throws SyntaxError {
        Token t = next();
        if (t == null) throw new SyntaxError(q(value) + "가 필요한데 문장이 끝났습니다");
        if (t.kind() != kind || !t.value().equals(value)) {
            throw new SyntaxError(q(value) + "가 필요한데 " + q(t.value()) + "가 왔습니다");
        }
    }

    private InsertStmt insert() throws SyntaxError {
        Token t = next();
        if (t.kind() != Kind.KEYWORD || !t.value().equals("INSERT")) {
            throw new SyntaxError("지원하지 않는 명령어: " + q(t.value()));
        }
        expect(Kind.KEYWORD, "INTO");
        Token name = next();
        if (name == null || name.kind() != Kind.IDENT) throw new SyntaxError("테이블 이름이 필요합니다");
        expect(Kind.KEYWORD, "VALUES");
        expect(Kind.SYMBOL, "(");
        List<Token> values = new ArrayList<>();
        while (true) {
            Token v = next();
            if (v == null || (v.kind() != Kind.NUMBER && v.kind() != Kind.STRING)) {
                throw new SyntaxError("값(숫자 또는 '문자열')이 필요합니다");
            }
            values.add(v);
            Token sep = next();
            if (new Token(Kind.SYMBOL, ")").equals(sep)) break;
            if (!new Token(Kind.SYMBOL, ",").equals(sep)) throw new SyntaxError("',' 또는 ')'가 필요합니다");
        }
        expect(Kind.SYMBOL, ";");
        return new InsertStmt(name.value(), values);
    }
}
