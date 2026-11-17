package minidb.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import minidb.parser.Token.Kind;

public final class Lexer {
    /** 오류가 나도 그 전까지의 토큰은 함께 돌려준다. error는 오류가 없으면 null. */
    public record Result(List<Token> tokens, String error) {}

    // 키워드는 대소문자 무시(대문자로 정규화), 식별자는 원문 유지.
    private static final Set<String> KEYWORDS = Set.of("INSERT", "INTO", "VALUES", "SELECT", "FROM", "WHERE");

    private Lexer() {}

    public static Result lex(String src) {
        List<Token> toks = new ArrayList<>();
        int n = src.length();
        for (int i = 0; i < n; ) {
            char c = src.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else if (src.startsWith("--", i)) { // 줄 끝까지 주석
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (src.startsWith("/*", i)) { // 처음 만나는 */ 까지 주석(중첩 없음)
                int end = src.indexOf("*/", i + 2);
                if (end < 0) return new Result(toks, "주석이 닫히지 않았습니다");
                i = end + 2;
            } else if ("(),;*=".indexOf(c) >= 0) {
                toks.add(new Token(Kind.SYMBOL, String.valueOf(c)));
                i++;
            } else if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n) return new Result(toks, "문자열이 닫히지 않았습니다");
                    if (src.charAt(i) == '\'') {
                        if (i + 1 < n && src.charAt(i + 1) == '\'') { // '' → '
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    sb.append(src.charAt(i++));
                }
                toks.add(new Token(Kind.STRING, sb.toString()));
            } else if (isDigit(c) || c == '-') {
                int start = i++;
                while (i < n && isDigit(src.charAt(i))) i++;
                if (c == '-' && i == start + 1) return new Result(toks, "잘못된 숫자: -");
                toks.add(new Token(Kind.NUMBER, src.substring(start, i)));
            } else if (isLetter(c)) {
                int start = i;
                while (i < n && (isLetter(src.charAt(i)) || isDigit(src.charAt(i)))) i++;
                String word = src.substring(start, i);
                String up = word.toUpperCase();
                toks.add(KEYWORDS.contains(up) ? new Token(Kind.KEYWORD, up) : new Token(Kind.IDENT, word));
            } else {
                return new Result(toks, "알 수 없는 문자: '" + c + "'");
            }
        }
        return new Result(toks, null);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLetter(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
