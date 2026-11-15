package minidb;

/** 사용자에게 그대로 보여줄 수 있는 메시지를 가진 DB 오류. */
public class DbException extends Exception {
    public DbException(String message) {
        super(message);
    }

    public DbException(String message, Throwable cause) {
        super(message, cause);
    }
}
