package minidb;

import java.util.List;

/** SELECT 결과를 MySQL 스타일 표로 만든다. 끝에 "N rows". */
final class TablePrinter {
    private TablePrinter() {}

    static String format(List<String> columns, List<List<String>> rows) {
        int[] w = new int[columns.size()];
        for (int c = 0; c < w.length; c++) {
            w[c] = width(columns.get(c));
            for (List<String> r : rows) w[c] = Math.max(w[c], width(r.get(c)));
        }
        StringBuilder sb = new StringBuilder();
        String border = border(w);
        sb.append(border).append(line(columns, w)).append(border);
        if (!rows.isEmpty()) {
            for (List<String> r : rows) sb.append(line(r, w));
            sb.append(border);
        }
        return sb.append(rows.size()).append(" rows\n").toString();
    }

    private static String border(int[] w) {
        StringBuilder sb = new StringBuilder("+");
        for (int x : w) sb.append("-".repeat(x + 2)).append('+');
        return sb.append('\n').toString();
    }

    private static String line(List<String> cells, int[] w) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < w.length; c++) {
            String v = cells.get(c);
            sb.append(' ').append(v).append(" ".repeat(w[c] - width(v))).append(" |");
        }
        return sb.append('\n').toString();
    }

    // shortcut: 한글·한자·가나만 2칸으로 계산, 이모지나 값 안의 줄바꿈은 표가 어긋남. 필요해지면 East Asian Width 표로 교체.
    static int width(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            n += sc == Character.UnicodeScript.HANGUL || sc == Character.UnicodeScript.HAN
                    || sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA ? 2 : 1;
            i += Character.charCount(cp);
        }
        return n;
    }
}
