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

    /** scanIds가 알려준 오프셋에서 readRowAt으로 읽으면 readRows의 같은 순서 행이 나온다. */
    @Test
    void scanIdsGivesOffsetsThatReadRowAtCanRead() throws Exception {
        // 한글, 따옴표 안의 줄바꿈·쉼표·따옴표, 빈 줄, \r\n, 따옴표로 감싼 _id, 끝에 줄바꿈 없는 마지막 행
        Files.writeString(dir.resolve("t.csv"),
                "1,김철수,20\n\n7,\"줄\n바꿈, \"\"인용\"\"\",21\r\n\"3\",lee,22\r\n\r\n10,끝,23");
        List<Long> ids = new java.util.ArrayList<>();
        List<Long> offsets = new java.util.ArrayList<>();
        Storage.scanIds(dir, "t", (id, offset) -> {
            ids.add(id);
            offsets.add(offset);
        });
        assertEquals(List.of(1L, 7L, 3L, 10L), ids);
        assertEquals(0L, offsets.get(0));

        List<List<String>> rows = Storage.readRows(dir, "t");
        assertEquals(4, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(rows.get(i), Storage.readRowAt(dir, "t", offsets.get(i)), i + "번째 행");
        }
        assertEquals(List.of("7", "줄\n바꿈, \"인용\"", "21"), Storage.readRowAt(dir, "t", offsets.get(1)));
    }

    @Test
    void scanIdsAgreesWithAppendRowOffsets() throws DbException {
        List<Long> written = new java.util.ArrayList<>();
        for (int i = 1; i <= 300; i++) { // 행 하나가 readRowAt의 4096바이트 버퍼를 넘지는 않지만 파일은 여러 버퍼 크기
            written.add(Storage.appendRow(dir, "t", List.of(String.valueOf(i), "이름" + i + "\n둘째 줄", "x".repeat(i))));
        }
        written.add(Storage.appendRow(dir, "t", List.of("301", "긴 행", "y".repeat(10_000)))); // 버퍼보다 긴 행
        List<Long> scanned = new java.util.ArrayList<>();
        Storage.scanIds(dir, "t", (id, offset) -> scanned.add(offset));
        assertEquals(written, scanned);
        assertEquals(List.of("300", "이름300\n둘째 줄", "x".repeat(300)), Storage.readRowAt(dir, "t", written.get(299)));
        assertEquals(List.of("301", "긴 행", "y".repeat(10_000)), Storage.readRowAt(dir, "t", written.get(300)));
    }

    @Test
    void scanIdsErrors() throws Exception {
        Storage.scanIds(dir, "nofile", (id, offset) -> fail("파일이 없으면 부르지 않는다"));
        Files.writeString(dir.resolve("bad.csv"), "1,a\nabc,b\n");
        assertThrows(DbException.class, () -> Storage.scanIds(dir, "bad", (id, offset) -> {}));
        Files.writeString(dir.resolve("open.csv"), "1,\"안 닫힘\n");
        assertThrows(DbException.class, () -> Storage.scanIds(dir, "open", (id, offset) -> {}));
    }

    @Test
    void lockDataDirIsExclusiveUntilClosed() throws Exception {
        try (var lock = Storage.lockDataDir(dir)) {
            var ex = assertThrows(DbException.class, () -> Storage.lockDataDir(dir));
            assertTrue(ex.getMessage().contains("사용 중"), ex.getMessage());
        }
        Storage.lockDataDir(dir).close(); // 닫으면 다시 잡힌다
    }
}
