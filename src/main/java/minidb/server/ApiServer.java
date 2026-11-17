package minidb.server;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import minidb.DbException;
import minidb.executor.Executor;
import minidb.storage.Storage;

/**
 * POST /query로 SQL 한 문장을 받아 실행하는 HTTP 서버.
 * 실행 중에는 data/.lock을 쥐고 있어 CLI나 다른 서버가 같은 data를 쓰지 못한다.
 */
public final class ApiServer {
    /** 요청을 처리하는 고정 스레드 개수. 다 바쁘면 나머지 요청은 무제한 대기열에서 기다린다. */
    static final int THREAD_POOL_SIZE = 8;
    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 8080;
    /** stopServer가 진행 중인 요청이 끝나기를 기다리는 최대 초. */
    private static final int STOP_DELAY_SECONDS = 3;

    private final HttpServer server;
    private final ExecutorService threadPool;
    private final FileChannel dataLock;

    private ApiServer(HttpServer server, ExecutorService threadPool, FileChannel dataLock) {
        this.server = server;
        this.threadPool = threadPool;
        this.dataLock = dataLock;
    }

    /** 127.0.0.1(이 컴퓨터에서만 접근):8080에 띄운다. */
    public static void main(String[] args) {
        Path dataDir = Path.of("data"); // 실행 위치 기준(CLI와 같음)
        ApiServer s;
        try {
            s = startServer(DEFAULT_HOST, DEFAULT_PORT, dataDir);
        } catch (DbException | IOException e) {
            System.err.println("오류: " + e.getMessage());
            System.exit(1);
            return;
        }
        // Ctrl+C·kill: 새 요청을 막고 진행 중인 요청(CSV 쓰기)이 끝나기를 기다린 뒤 종료
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                s.stopServer();
                System.out.println("minidb API 서버 종료");
            } catch (IOException e) {
                System.err.println("오류: 종료 중 " + e);
            }
        }));
        System.out.println("minidb API 서버: http://" + DEFAULT_HOST + ":" + s.port() + "/query (data: " + dataDir + ")");
    }

    /** data 락을 잡고 서버를 띄운다. port가 0이면 빈 포트를 고른다(테스트용). 락을 못 잡으면 DbException. */
    public static ApiServer startServer(String host, int port, Path dataDir) throws DbException, IOException {
        FileChannel dataLock = Storage.lockDataDir(dataDir);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            dataLock.close(); // 포트를 못 열었으면 data도 놓아준다
            throw e;
        }
        server.createContext("/query", new QueryHandler(new Executor(dataDir)));
        ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        server.setExecutor(threadPool);
        server.start();
        return new ApiServer(server, threadPool, dataLock);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** 새 요청을 받지 않고, 진행 중인 요청을 최대 STOP_DELAY_SECONDS 기다린 뒤 스레드와 data 락을 놓는다. */
    public void stopServer() throws IOException {
        server.stop(STOP_DELAY_SECONDS);
        threadPool.shutdown();
        dataLock.close();
    }
}
