# mini-dbms
Krafton Jungle Week 11-12 | 미니 DBMS 구현 (Java)

## 준비
JDK 17 이상(21 권장)만 설치하면 됩니다. Gradle은 `gradlew`가 알아서 내려받습니다.

```bash
java -version   # 확인
```

## 실행
```bash
./gradlew test                                   # 테스트
./gradlew run --args="examples/insert.sql"       # 실행 (data/ 는 프로젝트 루트 기준)
./gradlew run --args="examples/select.sql"       # SELECT * 결과를 표로 출력
```

Windows는 `./gradlew` 대신 `gradlew.bat`을 쓰세요.

## 구조
```
src/main/java/minidb/
  Main.java        진입점 (문장 종류별로 실행, 결과 출력)
  TablePrinter     SELECT 결과를 표 + "N rows"로 출력
  parser/          Lexer, Parser → Statement(InsertStmt | SelectStmt)
  executor/        Executor (INSERT 실행·id 자동 부여, SELECT * 조회)
  storage/         Storage (schema JSON + CSV 읽기/쓰기)
data/              <table>.schema, <table>.csv
```
