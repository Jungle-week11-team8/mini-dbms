package minidb.parser;

/** 파서가 만드는 문장. 새 명령어는 여기 permits에 추가한다. */
public sealed interface Statement permits InsertStmt, SelectStmt {}
