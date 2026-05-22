package org.mtype.editor.ui.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DiffComputer {
    private DiffComputer() {}

    public enum Op { EQUAL, DELETE, INSERT, REPLACE }

    /** One row in the side-by-side view. Either side may be null (padding line). */
    public record Row(String left, String right, Op op) {}

    public static List<Row> diff(String leftText, String rightText) {
        List<String> a = splitLines(leftText);
        List<String> b = splitLines(rightText);
        int[][] dp = lcsDp(a, b);

        // First pass: walk the LCS to produce a flat list of EQUAL/DELETE/INSERT.
        record Step(Op op, String left, String right) {}
        List<Step> steps = new ArrayList<>();
        int i = 0, j = 0, n = a.size(), m = b.size();
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                steps.add(new Step(Op.EQUAL, a.get(i), b.get(j)));
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                steps.add(new Step(Op.DELETE, a.get(i), null));
                i++;
            } else {
                steps.add(new Step(Op.INSERT, null, b.get(j)));
                j++;
            }
        }
        while (i < n) { steps.add(new Step(Op.DELETE, a.get(i++), null)); }
        while (j < m) { steps.add(new Step(Op.INSERT, null, b.get(j++))); }

        // Second pass: collapse adjacent DELETE+INSERT runs into REPLACE pairs so they
        // render aligned in the side-by-side view.
        List<Row> rows = new ArrayList<>();
        int k = 0;
        while (k < steps.size()) {
            Step s = steps.get(k);
            if (s.op() == Op.EQUAL) {
                rows.add(new Row(s.left(), s.right(), Op.EQUAL));
                k++;
                continue;
            }
            // Gather the whole run of non-EQUAL steps
            int start = k;
            while (k < steps.size() && steps.get(k).op() != Op.EQUAL) k++;
            List<String> dels = new ArrayList<>();
            List<String> ins = new ArrayList<>();
            for (int t = start; t < k; t++) {
                Step st = steps.get(t);
                if (st.op() == Op.DELETE) dels.add(st.left());
                else ins.add(st.right());
            }
            int paired = Math.min(dels.size(), ins.size());
            for (int p = 0; p < paired; p++) {
                rows.add(new Row(dels.get(p), ins.get(p), Op.REPLACE));
            }
            for (int p = paired; p < dels.size(); p++) {
                rows.add(new Row(dels.get(p), null, Op.DELETE));
            }
            for (int p = paired; p < ins.size(); p++) {
                rows.add(new Row(null, ins.get(p), Op.INSERT));
            }
        }
        return rows;
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        String[] parts = text.split("\n", -1);
        List<String> result = new ArrayList<>(Arrays.asList(parts));
        if (!result.isEmpty() && result.get(result.size() - 1).isEmpty() && text.endsWith("\n")) {
            result.remove(result.size() - 1);
        }
        for (int k = 0; k < result.size(); k++) {
            String line = result.get(k);
            if (line.endsWith("\r")) result.set(k, line.substring(0, line.length() - 1));
        }
        return result;
    }

    /** dp[i][j] = LCS length of a[i..n-1] and b[j..m-1]. */
    private static int[][] lcsDp(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a.get(i).equals(b.get(j))) dp[i][j] = dp[i + 1][j + 1] + 1;
                else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        return dp;
    }
}
