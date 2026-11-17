package minidb.parser;

import java.util.List;

/**
 * SELECT 컬럼들 FROM table [WHERE 컬럼 = 값];
 * columns의 "*"는 그 자리에 전체 컬럼을 펼친다(어디든, 여러 번 가능). where가 null이면 조건 없음.
 */
public record SelectStmt(String table, List<String> columns, Where where) implements Statement {
    /** value는 NUMBER 또는 STRING 토큰. */
    public record Where(String column, Token value) {}

    /** SELECT * FROM table; */
    public SelectStmt(String table) {
        this(table, List.of("*"), null);
    }
}
