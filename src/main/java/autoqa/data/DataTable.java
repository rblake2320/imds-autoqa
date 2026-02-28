package autoqa.data;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-driven test table — the IMDS AutoQA equivalent of UFT One's
 * {@code DataTable} object ({@code DataTable.Value}, {@code DataTable.Export}).
 *
 * <p>Reads parameterised test data from:
 * <ul>
 *   <li><b>CSV</b>  — first row = column headers; remaining rows = data rows</li>
 *   <li><b>XLS/XLSX</b> — first sheet; first row = headers (Apache POI)</li>
 * </ul>
 *
 * <p>Each data row is a {@code Map<String, String>} keyed by column header.
 * The DataTable can be passed directly into TestNG's {@code @DataProvider}
 * via {@link DataTableProvider}.
 *
 * <h3>UFT One Parity</h3>
 * <ul>
 *   <li>Named sheet support (Excel sheet by name or index)</li>
 *   <li>Iteration over rows with header-keyed access</li>
 *   <li>Empty-cell handling (returns "")</li>
 *   <li>Row filtering by column value</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * DataTable table = DataTable.fromCsv(Path.of("login-data.csv"));
 * for (Map<String,String> row : table.rows()) {
 *     String username = row.get("username");
 *     String password = row.get("password");
 * }
 * }</pre>
 */
public class DataTable {

    private static final Logger log = LoggerFactory.getLogger(DataTable.class);

    private final List<String>              headers;
    private final List<Map<String, String>> rows;

    // ── Constructors ─────────────────────────────────────────────────────────

    private DataTable(List<String> headers, List<Map<String, String>> rows) {
        this.headers = Collections.unmodifiableList(new ArrayList<>(headers));
        this.rows    = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Loads a data table from a CSV file (comma-separated, UTF-8).
     * First row is treated as the column header row.
     *
     * @throws IOException   if the file cannot be read
     * @throws DataException if the CSV is empty or malformed
     */
    public static DataTable fromCsv(Path path) throws IOException {
        log.debug("Loading CSV data table: {}", path);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVReader csv = new CSVReader(reader)) {

            List<String[]> all;
            try {
                all = csv.readAll();
            } catch (CsvException e) {
                throw new DataException("Failed to parse CSV: " + path, e);
            }

            if (all.isEmpty()) throw new DataException("CSV file is empty: " + path);

            List<String> headers = List.of(all.get(0));
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < all.size(); i++) {
                String[] rawRow = all.get(i);
                if (isBlankRow(rawRow)) continue;
                rows.add(buildRow(headers, rawRow));
            }
            log.info("CSV loaded: {} headers, {} data rows from {}", headers.size(), rows.size(), path.getFileName());
            return new DataTable(headers, rows);
        }
    }

    /**
     * Loads a data table from an Excel file (.xls or .xlsx).
     * Uses the first sheet and treats the first row as headers.
     *
     * @throws IOException   if the file cannot be read
     * @throws DataException if the workbook is empty or malformed
     */
    public static DataTable fromExcel(Path path) throws IOException {
        return fromExcel(path, 0);
    }

    /**
     * Loads a data table from a named sheet in an Excel file.
     *
     * @param sheetName exact sheet name (case-sensitive)
     * @throws DataException if the sheet is not found
     */
    public static DataTable fromExcel(Path path, String sheetName) throws IOException {
        log.debug("Loading Excel data table: {}!{}", path, sheetName);
        try (InputStream is = Files.newInputStream(path);
             Workbook wb = WorkbookFactory.create(is)) {

            Sheet sheet = wb.getSheet(sheetName);
            if (sheet == null) {
                throw new DataException("Sheet '" + sheetName + "' not found in " + path);
            }
            return parseSheet(sheet, path);
        }
    }

    /**
     * Loads a data table from a zero-based sheet index in an Excel file.
     *
     * @param sheetIndex 0-based sheet index
     */
    public static DataTable fromExcel(Path path, int sheetIndex) throws IOException {
        log.debug("Loading Excel data table: {}!sheet[{}]", path, sheetIndex);
        try (InputStream is = Files.newInputStream(path);
             Workbook wb = WorkbookFactory.create(is)) {

            if (sheetIndex >= wb.getNumberOfSheets()) {
                throw new DataException("Sheet index " + sheetIndex
                        + " out of range (workbook has " + wb.getNumberOfSheets() + " sheet(s))");
            }
            Sheet sheet = wb.getSheetAt(sheetIndex);
            return parseSheet(sheet, path);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Returns the ordered list of column headers. */
    public List<String> getHeaders() { return headers; }

    /** Returns all data rows (each row is a header-keyed map). */
    public List<Map<String, String>> rows() { return rows; }

    /** Number of data rows (excludes the header row). */
    public int rowCount() { return rows.size(); }

    /**
     * Returns rows where {@code column} equals {@code value}
     * (case-sensitive exact match).
     */
    public List<Map<String, String>> filterBy(String column, String value) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (value.equals(row.get(column))) result.add(row);
        }
        return result;
    }

    /**
     * Returns this table as a TestNG {@code Object[][]} — each inner array
     * contains a single {@code Map<String,String>} element so the
     * {@code @DataProvider} signature is {@code (Map<String,String> row)}.
     */
    public Object[][] toTestNgMatrix() {
        Object[][] matrix = new Object[rows.size()][1];
        for (int i = 0; i < rows.size(); i++) {
            matrix[i][0] = rows.get(i);
        }
        return matrix;
    }

    @Override
    public String toString() {
        return String.format("DataTable{headers=%s, rows=%d}", headers, rows.size());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static DataTable parseSheet(Sheet sheet, Path path) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) throw new DataException("Sheet '" + sheet.getSheetName() + "' has no header row");

        List<String> headers = new ArrayList<>();
        for (Cell cell : headerRow) {
            headers.add(cellString(cell));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, String> map = buildRow(headers, row);
            if (!map.values().stream().allMatch(String::isBlank)) {
                rows.add(map);
            }
        }
        log.info("Excel loaded: sheet='{}', {} headers, {} rows from {}",
                sheet.getSheetName(), headers.size(), rows.size(), path.getFileName());
        return new DataTable(headers, rows);
    }

    private static Map<String, String> buildRow(List<String> headers, String[] rawRow) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int col = 0; col < headers.size(); col++) {
            map.put(headers.get(col), col < rawRow.length ? rawRow[col] : "");
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> buildRow(List<String> headers, Row excelRow) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int col = 0; col < headers.size(); col++) {
            Cell cell = excelRow.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            map.put(headers.get(col), cell != null ? cellString(cell) : "");
        }
        return Collections.unmodifiableMap(map);
    }

    private static String cellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                // Return integer form when value is whole number (avoids "1.0")
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default      -> "";
        };
    }

    private static boolean isBlankRow(String[] row) {
        for (String cell : row) if (cell != null && !cell.isBlank()) return false;
        return true;
    }
}
