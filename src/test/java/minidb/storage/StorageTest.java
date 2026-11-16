package minidb.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import minidb.DbException;
import minidb.storage.Storage.Column;
import minidb.storage.Storage.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {
    @TempDir Path dir;

    @Test
    void loadSchema() throws Exception {
        Files.writeString(dir.resolve("students.schema"),
                "{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"name\",\"type\":\"TEXT\"}]}");
        assertEquals(new Schema(List.of(new Column("id", "INT"), new Column("name", "TEXT"))),
                Storage.loadSchema(dir, "students"));
    }

    @Test
    void loadSchemaMissingTable() {
        assertThrows(DbException.class, () -> Storage.loadSchema(dir, "nope"));
    }

    @Test
    void appendAndReadRows() throws DbException {
        assertTrue(Storage.readRows(dir, "students").isEmpty()); // CSV 파일이 아직 없으면 빈 테이블
        List<List<String>> want = List.of(List.of("1", "O'Brien, \"Jr\""), List.of("2", "줄\n바꿈"));
        for (List<String> r : want) Storage.appendRow(dir, "students", r);
        assertEquals(want, Storage.readRows(dir, "students"));
    }

    /** 반환값은 그 행이 시작하는 바이트 위치. 한글은 글자 수가 아니라 UTF-8 바이트 수로 센다. */
    @Test
    void appendRowReturnsByteOffsetOfTheRow() throws Exception {
        assertEquals(0, Storage.appendRow(dir, "students", List.of("1", "kim"))); // 파일이 없으면 0, "1,kim\n" = 6바이트
        assertEquals(6, Storage.appendRow(dir, "students", List.of("2", "김철수"))); // "2,김철수\n" = 2 + 9 + 1 = 12바이트
        long third = Storage.appendRow(dir, "students", List.of("3", "lee"));
        assertEquals(18, third);

        byte[] bytes = Files.readAllBytes(dir.resolve("students.csv"));
        assertEquals("3,lee\n", new String(bytes, (int) third, bytes.length - (int) third,
                java.nio.charset.StandardCharsets.UTF_8));
    }
}
