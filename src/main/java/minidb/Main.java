package minidb;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import minidb.executor.Executor;
import minidb.parser.InsertStmt;
import minidb.parser.Parser;

public final class Main {
    private static final Path DATA_DIR = Path.of("data"); // 실행 위치 기준

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, DATA_DIR, System.out, System.err));
    }

    /** 문장을 순서대로 실행하고 첫 오류에서 멈춘다. 앞서 실행된 INSERT는 그대로 남는다. */
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
        Parser.Result parsed = Parser.parse(src);
        Executor e = new Executor(dataDir);
        int i = 0;
        for (InsertStmt s : parsed.stmts()) {
            i++;
            try {
                e.insert(s);
            } catch (DbException ex) {
                err.println("오류: " + i + "번째 문장: " + ex.getMessage());
                return 1;
            }
            out.println("INSERT 1");
        }
        if (parsed.error() != null) {
            err.println("오류: " + parsed.error());
            return 1;
        }
        return 0;
    }
}
