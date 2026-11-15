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
}
