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
./gradlew run --args="examples/select.sql"       # SELECT (*, 컬럼 지정, WHERE 컬럼 = 값) 결과를 표로 출력
```

Windows는 `./gradlew` 대신 `gradlew.bat`을 쓰세요.

## 구조
```
src/main/java/minidb/
  Main.java        진입점 (문장 종류별로 실행, 결과 출력)
  TablePrinter     SELECT 결과를 표 + "N rows"로 출력
  parser/          Lexer, Parser → Statement(InsertStmt | SelectStmt)
  executor/        Executor (INSERT 실행·_id 자동 부여, SELECT 컬럼 선택·WHERE = 필터)
  storage/         Storage (schema JSON + CSV 읽기/쓰기)
data/              <table>.schema, <table>.csv
```

모든 테이블에는 시스템 컬럼 `_id`(INT)가 자동으로 붙습니다.
- CSV의 첫 필드로 저장되고, `.schema`에는 적지 않습니다(`_id`라는 이름을 쓰면 오류).
- INSERT의 VALUES에는 `.schema`의 컬럼만 순서대로 넣습니다.
- `SELECT *`에서 맨 앞에 보이고, `SELECT _id`나 `WHERE _id = 1`로 조회할 수 있습니다.

```sql
SELECT name, * FROM students WHERE _id = 2;   -- * 는 어디든, 여러 번 쓸 수 있음
```
