package minidb.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import minidb.DbException;
import minidb.executor.Executor;
import minidb.index.BPlusTree;
import minidb.parser.InsertStmt;
import minidb.parser.SelectStmt;
import minidb.parser.Token;
import minidb.parser.Token.Kind;

/**
 * 100만 건에서 _id 조회(인덱스)와 다른 필드 조회(선형 탐색)의 속도를 비교한다. JUnit 테스트가 아니며 gradlew bench로 실행한다.
 * 데이터는 build/bench에 매번 새로 만들고, 결과는 콘솔과 build/bench/result.txt에 남긴다.
 */
public final class Benchmark {
    static final String TABLE = "bench";
    static final int DIRECT_ROWS = 990_000; // CSV에 직접 쓰는 행
    static final int INSERT_ROWS = 10_000; // Executor.insert로 넣는 행
    static final int TOTAL_ROWS = DIRECT_ROWS + INSERT_ROWS;
    static final int INDEX_LOOKUPS = 10_000;
    static final int INDEX_WARMUP = 500;
    static final int LINEAR_LOOKUPS = 5;
    static final int BATCH = 10_000; // 배치 시나리오의 건별 조회 수
    static final int[] MAX_KEYS = {4, 16, 64, 128, 256};
    static final long SEED = 42;

    private static final StringBuilder report = new StringBuilder();

    private Benchmark() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/bench");
        resetDir(dir);
        Files.writeString(dir.resolve(TABLE + ".schema"),
                "{\"columns\":[{\"name\":\"name\",\"type\":\"TEXT\"},{\"name\":\"num\",\"type\":\"INT\"}]}");

        say("== 데이터 준비 (" + TOTAL_ROWS + "행: _id = k, name = 'user_k', num = k) ==");
        long t = System.nanoTime();
        writeRowsDirectly(dir);
        say(String.format("CSV 직접 생성 %,d행: %s", DIRECT_ROWS, ms(System.nanoTime() - t)));

        t = System.nanoTime();
        Executor writer = new Executor(dir);
        for (int k = DIRECT_ROWS + 1; k <= TOTAL_ROWS; k++) {
            writer.insert(new InsertStmt(TABLE,
                    List.of(new Token(Kind.STRING, "user_" + k), new Token(Kind.NUMBER, String.valueOf(k)))));
        }
        say(String.format("Executor.insert %,d행(첫 INSERT의 %,d행 재구축 포함): %s",
                INSERT_ROWS, DIRECT_ROWS, ms(System.nanoTime() - t)));
        say(String.format("CSV 크기: %,d바이트", Files.size(dir.resolve(TABLE + ".csv"))));

        long[] ids = new Random(SEED).longs(INDEX_WARMUP + INDEX_LOOKUPS, 1, TOTAL_ROWS + 1L).toArray();

        say("");
        say("== 인덱스 조회: WHERE _id = k ==");
        Executor e = new Executor(dir); // 새 Executor: 첫 _id 조회에서 100만 행을 훑어 인덱스를 만든다
        t = System.nanoTime();
        check(e, byId(ids[0]), ids[0]);
        long rebuild = System.nanoTime() - t;
        say(String.format("재구축(첫 조회 1번 포함, 예열 없음): %s", ms(rebuild)));

        for (int i = 0; i < INDEX_WARMUP; i++) check(e, byId(ids[i]), ids[i]); // 예열, 결과에서 제외
        t = System.nanoTime();
        for (int i = INDEX_WARMUP; i < ids.length; i++) check(e, byId(ids[i]), ids[i]);
        long indexTotal = System.nanoTime() - t;
        double indexAvg = (double) indexTotal / INDEX_LOOKUPS;
        say(String.format("조회 %,d번: 총 %s, 1건당 평균 %s", INDEX_LOOKUPS, ms(indexTotal), ms(indexAvg)));

        say("");
        say("== 선형 탐색 (예열 1번 후 " + LINEAR_LOOKUPS + "번 평균) ==");
        double numAvg = linear(e, ids, false);
        say(String.format("WHERE num = k        1건당 평균 %s", ms(numAvg)));
        double nameAvg = linear(e, ids, true);
        say(String.format("WHERE name = 'user_k' 1건당 평균 %s", ms(nameAvg)));

        say("");
        say("== 비교 ==");
        say(String.format("조회 1건당: 인덱스 %s / 선형 INT %s (%,.0f배) / 선형 TEXT %s (%,.0f배)",
                ms(indexAvg), ms(numAvg), numAvg / indexAvg, ms(nameAvg), nameAvg / indexAvg));
        double batchIndex = rebuild + indexAvg * BATCH;
        say(String.format("배치(건별 조회 %,d번) 총 시간:", BATCH));
        say(String.format("  인덱스(재구축 포함, 실측): %s", ms(batchIndex)));
        say(String.format("  선형 INT (평균×%,d 환산): %s (%,.0f배)", BATCH, ms(numAvg * BATCH), numAvg * BATCH / batchIndex));
        say(String.format("  선형 TEXT(평균×%,d 환산): %s (%,.0f배)", BATCH, ms(nameAvg * BATCH), nameAvg * BATCH / batchIndex));
        say(String.format("재구축 비용 회수: 조회 %.1f번부터 인덱스가 유리(선형 INT 기준)", rebuild / (numAvg - indexAvg)));

        say("");
        say(String.format("== 노드당 최대 키 개수 비교 (트리만, 증가 순서 %,d건 삽입 후 무작위 %,d번 조회) ==", TOTAL_ROWS, TOTAL_ROWS));
        long[] keys = new Random(SEED).longs(TOTAL_ROWS, 1, TOTAL_ROWS + 1L).toArray();
        treeRun(32, 200_000, keys); // 예열, 결과에서 제외
        say(String.format("%8s %12s %14s %16s", "maxKeys", "삽입 총", "조회 총", "조회 1건당"));
        for (int maxKeys : MAX_KEYS) {
            long[] r = treeRun(maxKeys, TOTAL_ROWS, keys);
            say(String.format("%8d %12s %14s %13.1f ns", maxKeys, ms(r[0]), ms(r[1]), (double) r[1] / keys.length));
        }

        Files.writeString(dir.resolve("result.txt"), report, StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("결과 저장: " + dir.resolve("result.txt"));
    }

    /** 선형 탐색 1번 예열 후 LINEAR_LOOKUPS번의 평균(ns). */
    private static double linear(Executor e, long[] ids, boolean byName) throws DbException {
        check(e, byField(ids[0], byName), ids[0]);
        long t = System.nanoTime();
        for (int i = 1; i <= LINEAR_LOOKUPS; i++) check(e, byField(ids[i], byName), ids[i]);
        return (double) (System.nanoTime() - t) / LINEAR_LOOKUPS;
    }

    /** {삽입 ns, 조회 ns}. 조회 결과가 틀리면 예외. */
    private static long[] treeRun(int maxKeys, int n, long[] keys) {
        BPlusTree tree = new BPlusTree(maxKeys);
        long t = System.nanoTime();
        for (long k = 1; k <= n; k++) tree.insert(k, k * 2);
        long insert = System.nanoTime() - t;
        long sum = 0;
        t = System.nanoTime();
        for (long k : keys) {
            if (k <= n) sum += tree.search(k).getAsLong();
        }
        long search = System.nanoTime() - t;
        long want = 0;
        for (long k : keys) {
            if (k <= n) want += k * 2;
        }
        if (sum != want) throw new IllegalStateException("maxKeys=" + maxKeys + ": 조회 결과가 틀립니다");
        return new long[] {insert, search};
    }

    private static SelectStmt byId(long k) {
        return new SelectStmt(TABLE, List.of("*"), new SelectStmt.Where("_id", new Token(Kind.NUMBER, String.valueOf(k))));
    }

    private static SelectStmt byField(long k, boolean byName) {
        Token v = byName ? new Token(Kind.STRING, "user_" + k) : new Token(Kind.NUMBER, String.valueOf(k));
        return new SelectStmt(TABLE, List.of("*"), new SelectStmt.Where(byName ? "name" : "num", v));
    }

    /** 세 조회 방식 모두 같은 행 하나를 돌려줘야 한다. */
    private static void check(Executor e, SelectStmt s, long k) throws DbException {
        List<List<String>> rows = e.select(s).rows();
        String id = String.valueOf(k);
        if (!rows.equals(List.of(List.of(id, "user_" + k, id)))) {
            throw new IllegalStateException("조회 결과가 틀립니다: k=" + k + ", " + (rows.size() > 3 ? rows.size() + "행" : rows));
        }
    }

    private static void writeRowsDirectly(Path dir) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve(TABLE + ".csv"), StandardCharsets.UTF_8)) {
            for (int k = 1; k <= DIRECT_ROWS; k++) {
                w.write(k + ",user_" + k + "," + k + "\n");
            }
        }
    }

    private static void resetDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        Files.createDirectories(dir);
    }

    private static String ms(double nanos) {
        double v = nanos / 1e6;
        return v >= 100 ? String.format("%,.0f ms", v) : v >= 1 ? String.format("%.1f ms", v) : String.format("%.4f ms", v);
    }

    private static void say(String line) {
        System.out.println(line);
        report.append(line).append('\n');
    }
}
