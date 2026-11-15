package minidb;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TablePrinterTest {
    @Test
    void koreanCountsAsTwoColumns() {
        assertEquals("""
                +------+-------+
                | name | v     |
                +------+-------+
                | 김철 | a한글 |
                +------+-------+
                1 rows
                """, TablePrinter.format(List.of("name", "v"), List.of(List.of("김철", "a한글"))));
    }
}
