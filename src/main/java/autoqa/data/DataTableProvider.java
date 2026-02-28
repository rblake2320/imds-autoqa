package autoqa.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.DataProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * TestNG {@link DataProvider} bridge for {@link DataTable}.
 *
 * <p>Extend this class in your test class and call the static helpers to
 * supply rows from a CSV or Excel file into your {@code @Test} methods.
 *
 * <p>UFT One parity: this mirrors UFT's <em>Data Table</em> test parameter
 * binding, where each row drives a separate test iteration.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * public class LoginTest extends DataTableProvider {
 *
 *     @DataProvider(name = "loginRows")
 *     public static Object[][] loginData() throws IOException {
 *         return DataTableProvider.csvRows("src/test/resources/login-data.csv");
 *     }
 *
 *     @Test(dataProvider = "loginRows")
 *     public void testLogin(Map<String, String> row) {
 *         String user = row.get("username");
 *         String pass = row.get("password");
 *         // ...
 *     }
 * }
 * }</pre>
 */
public abstract class DataTableProvider {

    private static final Logger log = LoggerFactory.getLogger(DataTableProvider.class);

    /**
     * Loads all rows from a CSV file as a TestNG data matrix.
     *
     * @param csvPath path to the CSV file (relative to working directory)
     * @return {@code Object[][]} — each row is {@code {Map<String,String>}}
     * @throws IOException if the file cannot be read
     */
    public static Object[][] csvRows(String csvPath) throws IOException {
        DataTable table = DataTable.fromCsv(Path.of(csvPath));
        log.info("DataProvider: {} rows from CSV '{}'", table.rowCount(), csvPath);
        return table.toTestNgMatrix();
    }

    /**
     * Loads all rows from an Excel file (first sheet) as a TestNG data matrix.
     *
     * @param excelPath path to the .xls or .xlsx file
     * @return {@code Object[][]} — each row is {@code {Map<String,String>}}
     * @throws IOException if the file cannot be read
     */
    public static Object[][] excelRows(String excelPath) throws IOException {
        DataTable table = DataTable.fromExcel(Path.of(excelPath));
        log.info("DataProvider: {} rows from Excel '{}'", table.rowCount(), excelPath);
        return table.toTestNgMatrix();
    }

    /**
     * Loads rows from a named sheet of an Excel file.
     *
     * @param excelPath  path to the .xls or .xlsx file
     * @param sheetName  exact sheet name
     * @return {@code Object[][]} — each row is {@code {Map<String,String>}}
     * @throws IOException if the file cannot be read
     */
    public static Object[][] excelRows(String excelPath, String sheetName) throws IOException {
        DataTable table = DataTable.fromExcel(Path.of(excelPath), sheetName);
        log.info("DataProvider: {} rows from Excel '{}!{}'", table.rowCount(), excelPath, sheetName);
        return table.toTestNgMatrix();
    }

    /**
     * Convenience: casts the raw TestNG parameter to the expected map type.
     * Call this at the top of a {@code @Test(dataProvider = ...)} method body
     * when the parameter type must stay as {@code Object} for TestNG compat.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> asRow(Object obj) {
        return (Map<String, String>) obj;
    }
}
