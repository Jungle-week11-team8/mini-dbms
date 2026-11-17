package minidb.server;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import minidb.DbException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTP 스모크: 실제 서버를 빈 포트로 띄워 상태코드와 JSON 모양만 확인한다(동시성은 ExecutorTest). */
class ApiServerTest {
    @TempDir Path dir;
    ApiServer server;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Files.writeString(dir.resolve("students.schema"),
                "{\"columns\":[{\"name\":\"name\",\"type\":\"TEXT\"},{\"name\":\"age\",\"type\":\"INT\"}]}");
        server = ApiServer.startServer("127.0.0.1", 0, dir);
    }

    @AfterEach
    void stop() throws Exception {
        server.stopServer();
    }

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> query(String sql) throws Exception {
        return send("POST", "/query", sql);
    }

    @Test
    void insertThenSelect() throws Exception {
        var r = query("INSERT INTO students VALUES ('김철수', 20);");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("{\"inserted\":1,\"id\":1}", r.body());
        assertEquals("application/json; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""));
        query("INSERT INTO students VALUES ('O''Brien', 21);");

        r = query("SELECT * FROM students WHERE _id = 1;");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("{\"columns\":[\"_id\",\"name\",\"age\"],\"rows\":[[\"1\",\"김철수\",\"20\"]]}", r.body());
        assertEquals("{\"columns\":[\"name\"],\"rows\":[[\"김철수\"],[\"O'Brien\"]]}", query("SELECT name FROM students;").body());
    }

    @Test
    void sqlErrorsAre400WithMessage() throws Exception {
        var r = query("SELECT * FROM nope;");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().startsWith("{\"error\":\"") && r.body().contains("nope"), r.body());
        assertEquals(400, query("INSERT INTO students VALUES (20, '김');").statusCode()); // 타입
        assertEquals(400, query("SELEC * FROM students").statusCode()); // 문법
        assertEquals(400, query("").statusCode()); // 문장 0개
    }

    @Test
    void multipleStatementsAre400AndNothingRuns() throws Exception {
        var r = query("INSERT INTO students VALUES ('a', 1); INSERT INTO students VALUES ('b', 2);");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("1개"), r.body());
        assertEquals(400, query("INSERT INTO students VALUES ('a', 1); SELEC").statusCode());
        assertFalse(Files.exists(dir.resolve("students.csv")), "검증 실패한 요청이 실행됨");
    }

    @Test
    void methodPathAndSizeErrors() throws Exception {
        var r = send("GET", "/query", null);
        assertEquals(405, r.statusCode());
        assertEquals("POST", r.headers().firstValue("Allow").orElse(""));
        assertEquals(404, send("POST", "/query/x", "SELECT * FROM students").statusCode());
        assertEquals(404, send("POST", "/other", "SELECT * FROM students").statusCode());
        assertEquals(413, query("x".repeat(QueryHandler.MAX_BODY_BYTES + 1)).statusCode());
        assertEquals(200, query("SELECT * FROM students;").statusCode(), "큰 요청 뒤에도 서버가 계속 응답해야 함");
    }

    @Test
    void diskErrorIs500() throws Exception {
        Files.createDirectory(dir.resolve("students.csv")); // 파일 자리에 디렉터리: 읽기에서 IOException
        var r = query("SELECT * FROM students;");
        assertEquals(500, r.statusCode(), r.body());
        assertTrue(r.body().startsWith("{\"error\":"), r.body());
    }

    @Test
    void secondServerOnSameDataDirFailsUntilStopped() throws Exception {
        var ex = assertThrows(DbException.class, () -> ApiServer.startServer("127.0.0.1", 0, dir));
        assertTrue(ex.getMessage().contains("사용 중"), ex.getMessage());
        server.stopServer();
        server = ApiServer.startServer("127.0.0.1", 0, dir); // 멈추면 락이 풀려 다시 뜬다(AfterEach가 멈춤)
    }
}
