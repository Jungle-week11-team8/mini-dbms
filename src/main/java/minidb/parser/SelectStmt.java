package minidb.parser;

/** SELECT * FROM table; (이번 유닛은 * 만 지원) */
public record SelectStmt(String table) implements Statement {}
