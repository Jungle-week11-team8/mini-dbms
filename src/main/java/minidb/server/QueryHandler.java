package minidb.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import minidb.DbException;
import minidb.executor.Executor;
import minidb.parser.InsertStmt;
import minidb.parser.Parser;
import minidb.parser.SelectStmt;
import minidb.parser.Statement;

/**
 * POST /query: 본문은 UTF-8 SQL 한 문장, 응답은 JSON.
 * 성공 200: SELECT {"columns":[...],"rows":[[...]]} / INSERT {"inserted":1,"id":n}
 * 실패: {"error":"메시지"}와 함께 400(요청·SQL 잘못), 404, 405, 413(본문 1MB 초과), 500(디스크 오류·예상 못 한 예외).
 */
final class QueryHandler implements HttpHandler {
    static final int MAX_BODY_BYTES = 1 << 20;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create(); // 스레드 안전

    record InsertResponse(int inserted, long id) {}

    record ErrorResponse(String error) {}

    /** 실패 응답으로 바로 돌려보낼 오류. */
    private static final class RequestException extends Exception {
        final int status;

        RequestException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Executor executor; // 서버 전체가 공유. 테이블 락은 Executor 안에서 잡는다

    QueryHandler(Executor executor) {
        this.executor = executor;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                if (!exchange.getRequestURI().getPath().equals("/query")) { // 컨텍스트는 접두어로 매칭돼 /query/x도 들어온다
                    throw new RequestException(404, "없는 경로입니다: " + exchange.getRequestURI().getPath());
                }
                if (!exchange.getRequestMethod().equals("POST")) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    throw new RequestException(405, "POST만 지원합니다");
                }
                Statement stmt = parseSingleStatement(readRequestBody(exchange));
                writeJsonResponse(exchange, 200, executeStatement(stmt));
            } catch (RequestException e) {
                writeErrorResponse(exchange, e.status, e.getMessage());
            } catch (DbException e) {
                // Storage는 파일 IOException을 cause로 감싼다: 서버 쪽 문제. 그 외는 SQL·스키마·데이터 쪽 문제.
                writeErrorResponse(exchange, e.getCause() instanceof IOException ? 500 : 400, e.getMessage());
            } catch (RuntimeException e) {
                e.printStackTrace();
                writeErrorResponse(exchange, 500, "서버 내부 오류: " + e);
            }
        }
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException, RequestException {
        InputStream in = exchange.getRequestBody();
        byte[] body = in.readNBytes(MAX_BODY_BYTES + 1); // 한 바이트 더 읽어 초과 여부를 안다
        if (body.length > MAX_BODY_BYTES) throw new RequestException(413, "요청 본문이 1MB를 넘습니다");
        return new String(body, StandardCharsets.UTF_8);
    }

    /** 파싱 오류가 있거나 문장이 정확히 1개가 아니면 아무것도 실행하지 않고 400. */
    private static Statement parseSingleStatement(String sql) throws RequestException {
        Parser.Result parsed = Parser.parse(sql);
        if (parsed.error() != null) throw new RequestException(400, parsed.error());
        if (parsed.stmts().size() != 1) {
            throw new RequestException(400, "요청당 SQL 문장은 1개여야 합니다(받은 문장: " + parsed.stmts().size() + "개)");
        }
        return parsed.stmts().get(0);
    }

    private Object executeStatement(Statement stmt) throws DbException {
        if (stmt instanceof InsertStmt ins) return new InsertResponse(1, executor.insert(ins));
        if (stmt instanceof SelectStmt sel) return executor.select(sel);
        throw new IllegalStateException("처리하지 않는 문장 종류: " + stmt);
    }

    private static void writeJsonResponse(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void writeErrorResponse(HttpExchange exchange, int status, String message) throws IOException {
        writeJsonResponse(exchange, status, new ErrorResponse(message));
    }
}
