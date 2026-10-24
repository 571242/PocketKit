package cn.xianhua.pocketkit.media;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 031 图片表格识别的结构推断核心。
 *
 * <p>What it does: takes the text lines recognised in a photo, groups them into rows by vertical
 * overlap, splits each row into cells by horizontal gaps, and emits a table. That is table
 * <em>reconstruction from layout geometry</em>, which is exactly what a table photo gives you.
 *
 * <p>What it does not do: understand merged cells, nested tables, or a table drawn with no
 * whitespace between columns. The screen says so, and the result is shown before saving so the user
 * can correct it. A model that understands table semantics would be better and is not available with
 * a clear licence here, so this does the honest half rather than pretending.
 *
 * <p>All geometry is plain arithmetic over recognised boxes, so it is verifiable off-device.
 */
public final class TableCore {

    /** A recognised piece of text with its position, as the OCR pass reports it. */
    public static final class Cell {
        public final String text;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Cell(String text, int left, int top, int right, int bottom) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int centerY() {
            return (top + bottom) / 2;
        }

        public int height() {
            return Math.max(1, bottom - top);
        }
    }

    /** One output row: ordered cell texts plus the row's vertical centre. */
    public static final class Row {
        public final List<String> cells = new ArrayList<>();
        public final int centerY;

        Row(int centerY) {
            this.centerY = centerY;
        }
    }

    public static final class Table {
        public final List<Row> rows = new ArrayList<>();
        /** Widest row; the column count of the result. */
        public int columns;

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    private TableCore() {
    }

    /**
     * Groups text into a table.
     *
     * <p>Rows come from vertical overlap rather than a fixed threshold: a line's height varies with
     * font size and perspective, so two boxes belong to the same row when their vertical extents
     * overlap by a meaningful fraction of the shorter one. A fixed pixel threshold breaks as soon as
     * the photo is slightly rotated or the rows are unevenly spaced.
     *
     * @param columnGapRatio a horizontal gap wider than this fraction of the median character width
     *                        starts a new column
     */
    public static Table build(List<Cell> cells, double columnGapRatio) {
        Table table = new Table();
        if (cells == null || cells.isEmpty()) {
            return table;
        }
        List<Cell> sorted = new ArrayList<>(cells);
        sorted.sort(Comparator.comparingInt(Cell::centerY).thenComparingInt(c -> c.left));

        // Pass 1: group into rows by vertical overlap.
        List<List<Cell>> rowGroups = new ArrayList<>();
        for (Cell cell : sorted) {
            List<Cell> target = null;
            for (List<Cell> group : rowGroups) {
                if (overlapsVertically(group.get(0), cell)) {
                    target = group;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                rowGroups.add(target);
            }
            target.add(cell);
        }

        double medianCharWidth = medianCharWidth(sorted);
        double gapThreshold = Math.max(6.0, medianCharWidth * columnGapRatio);

        for (List<Cell> group : rowGroups) {
            group.sort(Comparator.comparingInt(c -> c.left));
            Row row = new Row(group.get(0).centerY());
            StringBuilder current = new StringBuilder();
            int previousRight = Integer.MIN_VALUE;
            for (Cell cell : group) {
                if (previousRight != Integer.MIN_VALUE) {
                    int gap = cell.left - previousRight;
                    if (gap > gapThreshold) {
                        row.cells.add(current.toString().trim());
                        current.setLength(0);
                    } else {
                        // Same cell: two recognised fragments separated by ordinary letter spacing.
                        current.append(' ');
                    }
                }
                current.append(cell.text);
                previousRight = cell.right;
            }
            row.cells.add(current.toString().trim());
            table.rows.add(row);
            table.columns = Math.max(table.columns, row.cells.size());
        }
        // Rows are emitted top to bottom, which is how a person reads a table.
        table.rows.sort(Comparator.comparingInt(r -> r.centerY));
        return table;
    }

    /** True when two boxes share a meaningful part of their vertical extent. */
    static boolean overlapsVertically(Cell a, Cell b) {
        int overlapTop = Math.max(a.top, b.top);
        int overlapBottom = Math.min(a.bottom, b.bottom);
        int overlap = overlapBottom - overlapTop;
        if (overlap <= 0) {
            // A small tolerance keeps a superscript or a slightly lower glyph in its row.
            int tolerance = (int) (Math.min(a.height(), b.height()) * 0.35);
            int gap = Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom);
            return gap <= tolerance;
        }
        int shorter = Math.min(a.height(), b.height());
        return overlap >= shorter * 0.4;
    }

    /** Median character width across all recognised text, used to scale the column gap. */
    static double medianCharWidth(List<Cell> cells) {
        List<Integer> widths = new ArrayList<>();
        for (Cell cell : cells) {
            int chars = Math.max(1, cell.text.codePointCount(0, cell.text.length()));
            widths.add(Math.max(1, (cell.right - cell.left) / chars));
        }
        if (widths.isEmpty()) {
            return 10;
        }
        widths.sort(null);
        return widths.get(widths.size() / 2);
    }

    /** Tab-separated output with a UTF-8 BOM, so a spreadsheet opens the Chinese columns correctly. */
    public static String toTsv(Table table) {
        StringBuilder sb = new StringBuilder();
        for (Row row : table.rows) {
            for (int i = 0; i < table.columns; i++) {
                if (i > 0) {
                    sb.append('\t');
                }
                sb.append(i < row.cells.size() ? row.cells.get(i) : "");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** CSV with quotes only where a field needs them. */
    public static String toCsv(Table table) {
        StringBuilder sb = new StringBuilder();
        for (Row row : table.rows) {
            for (int i = 0; i < table.columns; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String value = i < row.cells.size() ? row.cells.get(i) : "";
                if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                    sb.append('"').append(value.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(value);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Preview limited to a readable size; the full table goes to the exported file. */
    public static String preview(Table table, int maxRows, int maxCols) {
        if (table.isEmpty()) {
            return "没有识别到任何文字，因此无法推断表格结构。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(table.rows.size()).append(" 行，").append(table.columns).append(" 列。\n\n");
        int rows = Math.min(table.rows.size(), maxRows);
        for (int r = 0; r < rows; r++) {
            sb.append("│ ");
            Row row = table.rows.get(r);
            int cols = Math.min(row.cells.size(), maxCols);
            for (int c = 0; c < cols; c++) {
                String value = row.cells.get(c);
                if (value.length() > 14) {
                    value = value.substring(0, 13) + "…";
                }
                sb.append(value);
                if (c < cols - 1) {
                    sb.append(" │ ");
                }
            }
            sb.append(" │\n");
        }
        if (table.rows.size() > rows) {
            sb.append("…（还有 ").append(table.rows.size() - rows).append(" 行，导出文件里是完整的）\n");
        }
        return sb.toString();
    }
}
