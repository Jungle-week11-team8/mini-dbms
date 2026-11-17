-- 전체 조회
SELECT * FROM students;

/* Pick columns and filter rows with WHERE column = value. */
SELECT name, age FROM students WHERE name = 'kim';

-- 조건에 맞는 행이 없으면 0 rows
select _ID from students where age = 99; /* no student is 99 */
