package minidb;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import minidb.executor.Executor;
import minidb.executor.Executor.QueryResult;
import minidb.parser.InsertStmt;
import minidb.parser.Parser;
import minidb.parser.SelectStmt;
import minidb.parser.Statement;
import minidb.storage.Storage;

public final class Main {
    private static final Path DATA_DIR = Path.of("data"); // 실행 위치 기준

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, DATA_DIR, System.out, System.err));
    }

    /** 문장을 순서대로 실행하고 첫 오류에서 멈춘다. 앞서 실행된 문장의 결과는 그대로 남는다. */
    static int run(String[] args, Path dataDir, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("사용법: minidb <file.sql>");
            return 1;
        }
        String src;
        try {
            src = Files.readString(Path.of(args[0]));
        } catch (IOException e) {
            err.println("오류: " + e);
            return 1;
        }
        FileChannel dataLock; // 실행하는 동안 다른 CLI·서버가 같은 data를 쓰지 못하게
        try {
            dataLock = Storage.lockDataDir(dataDir);
        } catch (DbException ex) {
            err.println("오류: " + ex.getMessage());
            return 1;
        }
        try (dataLock) {
            return runStatements(Parser.parse(src), dataDir, out, err);
        } catch (IOException ex) {
            err.println("오류: 데이터 디렉터리 락을 풀 수 없습니다: " + ex);
            return 1;
        }
    }

    private static int runStatements(Parser.Result parsed, Path dataDir, PrintStream out, PrintStream err) {
        Executor e = new Executor(dataDir);
        int i = 0;
        for (Statement s : parsed.stmts()) {
            i++;
            try {
                if (s instanceof InsertStmt ins) {
                    e.insert(ins);
                    out.println("INSERT 1");
                } else if (s instanceof SelectStmt sel) {
                    QueryResult r = e.select(sel);
                    out.print(TablePrinter.format(r.columns(), r.rows()));
                }
            } catch (DbException ex) {
                err.println("오류: " + i + "번째 문장: " + ex.getMessage());
                return 1;
            }
        }
        if (parsed.error() != null) {
            err.println("오류: " + parsed.error());
            return 1;
        }
        return 0;
    }
}
