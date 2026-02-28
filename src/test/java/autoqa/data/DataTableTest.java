package autoqa.data;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DataTable} CSV loading and data access.
 *
 * <p>Excel tests are omitted here (require real .xlsx fixtures and POI
 * binary dependencies) — they are covered by manual smoke tests.
 */
public class DataTableTest {

    private static final String CSV_PATH = "src/test/resources/test-data.csv";

    @Test
    public void csvLoad_returnsCorrectRowCount() throws IOException {
        DataTable table = DataTable.fromCsv(Path.of(CSV_PATH));
        assertThat(table.rowCount()).isEqualTo(3);
    }

    @Test
    public void csvLoad_returnsCorrectHeaders() throws IOException {
        DataTable table = DataTable.fromCsv(Path.of(CSV_PATH));
        assertThat(table.getHeaders()).containsExactly("username", "password", "expectedTitle");
    }

    @Test
    public void csvLoad_firstRowValues() throws IOException {
        DataTable table = DataTable.fromCsv(Path.of(CSV_PATH));
        Map<String, String> row = table.rows().get(0);
        assertThat(row.get("username")).isEqualTo("admin");
        assertThat(row.get("password")).isEqualTo("secret");
        assertThat(row.get("expectedTitle")).isEqualTo("Dashboard");
    }

    @Test
    public void csvLoad_lastRowValues() throws IOException {
        DataTable table = DataTable.fromCsv(Path.of(CSV_PATH));
        Map<String, String> row = table.rows().get(2);
        assertThat(row.get("username")).isEqualTo("testuser");
        assertThat(row.get("expectedTitle")).isEqualTo("Welcome");
    }

    @Test
    public void filterBy_returnsMatchingRow() throws IOException {
        DataTable table = DataTable.fromCsv(Path.of(CSV_PATH));
        List<Map<String, String>> filtered = table.filterBy("username", "user1");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("password")).isEqualTo("pass123");
    }

    @Test
    public void filterBy_noMatch_returnsEmpty() throws IOException {
        DataTable table = DataTable.fromCsv(Path.of(CSV_PATH));
        assertThat(table.filterBy("username", "nonexistent")).isEmpty();
    }

    @Test
    public void toTestNgMatrix_dimensionsMatch() throws IOException {
        DataTable table = DataTable.fromCsv(Path.of(CSV_PATH));
        Object[][] matrix = table.toTestNgMatrix();
        assertThat(matrix).hasDimensions(3, 1);
        assertThat(matrix[0][0]).isInstanceOf(Map.class);
    }

    @Test
    public void csvLoad_nonExistentFile_throwsIOException() {
        assertThatThrownBy(() -> DataTable.fromCsv(Path.of("no-such-file.csv")))
                .isInstanceOf(IOException.class);
    }
}
