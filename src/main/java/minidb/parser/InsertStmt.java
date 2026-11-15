package minidb.parser;

import java.util.List;

/** values는 NUMBER 또는 STRING 토큰. */
public record InsertStmt(String table, List<Token> values) {}
