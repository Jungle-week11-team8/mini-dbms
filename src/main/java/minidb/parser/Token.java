package minidb.parser;

public record Token(Kind kind, String value) {
    public enum Kind { KEYWORD, IDENT, NUMBER, STRING, SYMBOL }
}
