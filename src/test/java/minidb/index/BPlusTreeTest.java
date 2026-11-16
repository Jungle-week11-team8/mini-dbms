package minidb.index;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BPlusTreeTest {
    @Test
    void emptyTreeFindsNothing() {
        assertEquals(OptionalLong.empty(), new BPlusTree(4).search(1));
    }

    @Test
    void maxKeysBelowThreeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BPlusTree(2));
        assertDoesNotThrow(() -> new BPlusTree(3));
    }

    @Test
    void insertAndSearchWithinOneLeaf() {
        BPlusTree t = new BPlusTree(4);
        t.insert(2, 200);
        t.insert(1, 100);
        t.insert(3, 0); // 오프셋 0도 정상 값
        assertEquals(OptionalLong.of(100), t.search(1));
        assertEquals(OptionalLong.of(200), t.search(2));
        assertEquals(OptionalLong.of(0), t.search(3));
        assertEquals(OptionalLong.empty(), t.search(4));
    }

    @Test
    void duplicateKeyIsRejected() {
        BPlusTree t = new BPlusTree(4);
        for (long k = 1; k <= 20; k++) t.insert(k, k);
        assertThrows(IllegalArgumentException.class, () -> t.insert(1, 999)); // 리프의 첫 키
        assertThrows(IllegalArgumentException.class, () -> t.insert(13, 999)); // 분할로 부모에 복사됐을 수 있는 키
        assertEquals(OptionalLong.of(13), t.search(13)); // 기존 값은 그대로
    }

    /** _id가 들어오는 실제 순서: 1부터 증가. 리프·내부 노드 분할이 여러 단계로 일어난다. */
    @Test
    void ascendingKeysSurviveManySplits() {
        for (int maxKeys : new int[] {3, 4, 5, 128}) {
            BPlusTree t = new BPlusTree(maxKeys);
            int n = 10_000;
            for (long k = 1; k <= n; k++) t.insert(k, k * 10);
            for (long k = 1; k <= n; k++) assertEquals(OptionalLong.of(k * 10), t.search(k), "maxKeys=" + maxKeys);
            assertEquals(OptionalLong.empty(), t.search(0));
            assertEquals(OptionalLong.empty(), t.search(n + 1));
        }
    }

    @Test
    void descendingKeys() {
        BPlusTree t = new BPlusTree(4);
        for (long k = 5_000; k >= 1; k--) t.insert(k, -k);
        for (long k = 1; k <= 5_000; k++) assertEquals(OptionalLong.of(-k), t.search(k));
    }

    @Test
    void randomOrderKeysWithGaps() {
        for (int maxKeys : new int[] {3, 4, 7}) {
            List<Long> keys = new ArrayList<>();
            for (long k = 0; k < 10_000; k++) keys.add(k * 2); // 짝수만 넣는다
            Collections.shuffle(keys, new Random(42));
            BPlusTree t = new BPlusTree(maxKeys);
            for (long k : keys) t.insert(k, k + 1);
            for (long k : keys) {
                assertEquals(OptionalLong.of(k + 1), t.search(k), "maxKeys=" + maxKeys);
                assertEquals(OptionalLong.empty(), t.search(k + 1)); // 사이의 홀수는 없음
            }
        }
    }

    @Test
    void negativeAndExtremeKeys() {
        BPlusTree t = new BPlusTree(3);
        long[] keys = {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE};
        for (long k : keys) t.insert(k, 7);
        for (long k : keys) assertEquals(OptionalLong.of(7), t.search(k));
    }
}
