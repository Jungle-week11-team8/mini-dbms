# 매뉴얼

[← README](../README.md)

## 준비
JDK 17 이상(21 권장)만 설치하면 됩니다. Gradle은 `gradlew`가 알아서 내려받습니다.

```bash
java -version   # 확인
```

## 실행
세 방법 모두 프로젝트 루트의 같은 `data/`를 씁니다.

```bash
./gradlew test                                   # 테스트
./gradlew run --args="examples/insert.sql"       # SQL 파일 실행(CLI), 예제는 examples/
./gradlew server                                 # API 서버 (127.0.0.1:8080), 종료는 Ctrl+C
./gradlew bench                                  # 벤치마크 (100만 건)
```

| 항목      | 동작                                                  |
|---------|-----------------------------------------------------|
| CLI     | SELECT 결과를 표 + `N rows`로 출력                         |
| 서버 종료   | `Ctrl+C` → 새 요청 차단 → 진행 중 요청을 최대 3초 기다린 뒤 종료        |
| 벤치마크 결과 | `build/bench/result.txt`에 저장 (`./gradlew test`와 별개) |

> CLI와 서버는 같은 `data/`를 **동시에 쓸 수 없습니다.** 나중에 실행한 쪽은 "다른 프로세스가 데이터 디렉터리를 사용 중" 오류로 바로 종료합니다.

Windows는 `./gradlew` 대신 `gradlew.bat`을 쓰세요.

<br/>

## API

### `POST /query`

- **요청**: SQL 한 문장, `;`로 끝나야 함 (UTF-8, 최대 1MB)

- **성공 응답** `200 OK`

| 쿼리 | 응답 |
|------|------|
| SELECT | `{"columns": [...], "rows": [[...]]}` |
| INSERT | `{"inserted": 1, "id": n}` (`n` = 새로 부여된 `_id`) |

<br/>

- **실패 응답**: `{"error": "메시지"}` (상태 코드는 아래 표 참고)

| 코드 | 언제 |
| --- | --- |
| 400 | SQL 문법·타입 오류, 없는 테이블·컬럼, 문장이 0개 또는 2개 이상 |
| 404 | `/query`가 아닌 경로 |
| 405 | POST가 아닌 메서드 |
| 413 | 본문이 1MB를 넘음 |
| 500 | 디스크 오류, 예상하지 못한 서버 오류 |

```bash
curl -X POST http://127.0.0.1:8080/query --data "INSERT INTO students VALUES ('김철수', 20);"
# {"inserted":1,"id":1}
curl -X POST http://127.0.0.1:8080/query --data "SELECT * FROM students WHERE _id = 1;"
# {"columns":["_id","name","age"],"rows":[["1","김철수","20"]]}
```

<br/>

## 지원 문법
```sql
INSERT INTO <table> VALUES (<값>, ...);                 -- 값은 정수 또는 '문자열'
SELECT <* | 컬럼, ...> FROM <table> [WHERE <컬럼> = <값>];
```

| 규칙 | 내용                                                      |
| --- |---------------------------------------------------------|
| 키워드 | 대소문자 무시, 문장은 `;`로 종결                                    |
| 문자열 | 작은따옴표는 `''`로 씀 (`'O''Brien'`)                           |
| 주석 | `--` 줄 끝까지, `/* */` 여러 줄(중첩 불가, 안 닫으면 오류). 문자열 안은 주석 아님 |
| 오류 (CLI) | 첫 오류에서 멈춤, 앞 문장 결과는 남음                                  |

<br/>

### 시스템 컬럼 `_id`
모든 테이블에 자동으로 붙는 INT 컬럼입니다.
- INSERT 때 자동 부여 → VALUES에는 `.schema`의 컬럼만 순서대로 넣습니다.
- `.schema`에는 적지 않습니다(같은 이름이면 오류). CSV에는 첫 필드로 저장됩니다.
- `WHERE _id = n`은 설정 없이 B+ 트리 인덱스로 조회합니다.

```sql
SELECT name, * FROM students WHERE _id = 2;   -- * 는 어디든, 여러 번 쓸 수 있음 (_id는 * 맨 앞)
```
