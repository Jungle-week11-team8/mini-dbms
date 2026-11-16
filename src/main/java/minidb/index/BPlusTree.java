package minidb.index;

import java.util.OptionalLong;

/**
 * 메모리 B+ 트리. long 키(_id) → long 값(CSV 바이트 오프셋).
 * 값은 리프에만 있고, 내부 노드의 키는 길 안내용 구분값이다(키 >= 구분값이면 오른쪽 자식).
 * 삭제와 리프 연결 리스트는 아직 없다.
 */
public final class BPlusTree {
    private final int maxKeys;
    private Node root;

    /** maxKeys: 노드 하나가 가질 수 있는 최대 키 개수. 3 미만이면 오류. */
    public BPlusTree(int maxKeys) {
        if (maxKeys < 3) throw new IllegalArgumentException("maxKeys는 3 이상이어야 합니다: " + maxKeys);
        this.maxKeys = maxKeys;
        this.root = new Leaf();
    }

    /** 이미 있는 키면 IllegalArgumentException(트리는 바뀌지 않는다). */
    public void insert(long key, long value) {
        Split s = root.insert(key, value);
        if (s == null) return;
        // 루트가 분할됨: 새 루트를 만들어 트리가 한 단계 높아진다.
        Internal r = new Internal();
        r.keys[0] = s.key;
        r.children[0] = root;
        r.children[1] = s.right;
        r.count = 1;
        root = r;
    }

    public OptionalLong search(long key) {
        Node n = root;
        while (n instanceof Internal in) n = in.children[in.childIndex(key)];
        Leaf leaf = (Leaf) n;
        int i = leaf.indexOf(key);
        return i >= 0 ? OptionalLong.of(leaf.values[i]) : OptionalLong.empty();
    }

    /** 분할 결과: 부모에 넣을 구분값과 새로 생긴 오른쪽 노드. */
    private record Split(long key, Node right) {}

    private abstract class Node {
        // 넘친 상태(maxKeys + 1개)를 잠시 담을 수 있도록 한 칸 여유를 둔다.
        final long[] keys = new long[maxKeys + 1];
        int count; // 사용 중인 키 개수

        /** 넘쳐서 분할했으면 Split, 아니면 null. */
        abstract Split insert(long key, long value);

        /** keys[0..count)에서 key보다 큰 첫 위치(없으면 count). */
        final int upperBound(long key) {
            int lo = 0, hi = count;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (keys[mid] <= key) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }
    }

    private final class Leaf extends Node {
        final long[] values = new long[maxKeys + 1];

        /** key의 위치, 없으면 -1. */
        int indexOf(long key) {
            int i = upperBound(key) - 1;
            return i >= 0 && keys[i] == key ? i : -1;
        }

        @Override
        Split insert(long key, long value) {
            int pos = upperBound(key);
            if (pos > 0 && keys[pos - 1] == key) throw new IllegalArgumentException("이미 있는 키입니다: " + key);
            System.arraycopy(keys, pos, keys, pos + 1, count - pos);
            System.arraycopy(values, pos, values, pos + 1, count - pos);
            keys[pos] = key;
            values[pos] = value;
            count++;
            if (count <= maxKeys) return null;

            // 반반 분할: 왼쪽에 절반을 남기고 나머지는 오른쪽으로. 오른쪽 첫 키를 복사해 올린다(리프에도 남음).
            int keep = count / 2;
            Leaf right = new Leaf();
            right.count = count - keep;
            System.arraycopy(keys, keep, right.keys, 0, right.count);
            System.arraycopy(values, keep, right.values, 0, right.count);
            count = keep;
            return new Split(right.keys[0], right);
        }
    }

    private final class Internal extends Node {
        // 자식은 키보다 하나 많다: children[i]는 keys[i]보다 작은 키, children[i + 1]은 keys[i] 이상인 키.
        final Node[] children = new Node[maxKeys + 2];

        /** key가 내려갈 자식 위치. */
        int childIndex(long key) {
            return upperBound(key);
        }

        @Override
        Split insert(long key, long value) {
            int pos = childIndex(key);
            Split s = children[pos].insert(key, value);
            if (s == null) return null;

            // 자식이 분할됨: 구분값과 새 오른쪽 자식을 pos 자리에 끼워 넣는다.
            System.arraycopy(keys, pos, keys, pos + 1, count - pos);
            System.arraycopy(children, pos + 1, children, pos + 2, count - pos);
            keys[pos] = s.key;
            children[pos + 1] = s.right;
            count++;
            if (count <= maxKeys) return null;

            // 반반 분할: 가운데 키는 빼서 위로 올린다(이 노드에도 오른쪽 노드에도 남지 않음).
            int mid = count / 2;
            long up = keys[mid];
            Internal right = new Internal();
            right.count = count - mid - 1;
            System.arraycopy(keys, mid + 1, right.keys, 0, right.count);
            System.arraycopy(children, mid + 1, right.children, 0, right.count + 1);
            java.util.Arrays.fill(children, mid + 1, count + 1, null); // 옮긴 자식 참조를 지운다
            count = mid;
            return new Split(up, right);
        }
    }
}
