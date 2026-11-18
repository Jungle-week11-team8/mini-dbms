package minidb.executor;

import java.nio.file.Path;
import minidb.DbException;
import minidb.index.BPlusTree;
import minidb.storage.Storage;

/**
 * 테이블 하나의 메모리 상태: _id 인덱스(_id → CSV 바이트 오프셋)와 다음 _id.
 * 스레드 안전하지 않다. 호출하는 쪽이 이 객체로 동기화(테이블 배타 락)한 상태에서만 부른다.
 */
final class TableState {
    /** 인덱스 노드당 최대 키 개수. 벤치마크 전까지의 임시값. */
    private static final int INDEX_MAX_KEYS = 128;

    private final Path dir;
    private final String table;
    private BPlusTree index; // 처음 필요할 때 CSV를 한 번 훑어 채운다
    private long nextId;

    TableState(Path dir, String table) {
        this.dir = dir;
        this.table = table;
    }

    /** 인덱스. 처음 부를 때 CSV를 한 번 훑어 만들고 다음 _id도 함께 정한다. 실패하면 아무것도 저장하지 않는다. */
    BPlusTree ensureIndexLoaded() throws DbException {
        if (index != null) return index;
        BPlusTree tree = new BPlusTree(INDEX_MAX_KEYS);
        long[] max = {0};
        Storage.scanIds(dir, table, (id, offset) -> {
            try {
                tree.insert(id, offset);
            } catch (IllegalArgumentException e) {
                throw new DbException(table + ".csv: 중복된 _id " + id);
            }
            max[0] = Math.max(max[0], id);
        });
        index = tree;
        nextId = max[0] + 1;
        return tree;
    }

    /** id를 하나 예약한다. 이후 appendRow가 실패하면 그 id는 건너뛰어진다(중복은 없음). */
    long reserveNextId() throws DbException {
        ensureIndexLoaded(); // nextId가 채워져 있도록
        return nextId++;
    }
}
