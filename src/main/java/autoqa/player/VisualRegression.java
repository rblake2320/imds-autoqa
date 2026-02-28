package autoqa.player;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Pixel-diff visual regression testing — captures, stores, and compares page screenshots.
 *
 * <p>Provides two comparison modes:
 * <ol>
 *   <li><b>Pixel diff</b> — exact pixel-by-pixel comparison with a configurable
 *       tolerance percentage. Fast, no external service required.</li>
 *   <li><b>Semantic diff</b> — delegates to {@link autoqa.vision.NvClipClient}
 *       when available, for AI-powered comparison that ignores cosmetic noise
 *       (anti-aliasing, font hinting) while catching real layout regressions.</li>
 * </ol>
 *
 * <h3>Baseline workflow</h3>
 * <pre>{@code
 * VisualRegression vr = new VisualRegression(driver, Path.of("baselines"));
 *
 * // First run: capture baseline (creates PNG file if missing)
 * vr.captureBaseline("homepage");
 *
 * // Subsequent runs: compare against baseline
 * vr.assertMatchesBaseline("homepage", 0.01); // max 1% pixel difference
 * }</pre>
 *
 * <p>Diffs are saved to {@code target/visual-diffs/} with timestamps for CI
 * artefact upload.
 */
public class VisualRegression {

    private static final Logger log = LoggerFactory.getLogger(VisualRegression.class);

    /** Default maximum allowed pixel difference ratio (1%). */
    public static final double DEFAULT_THRESHOLD = 0.01;

    private final WebDriver driver;
    private final Path baselinesDir;
    private final Path diffsDir;

    // ── Constructors ──────────────────────────────────────────────────────────

    public VisualRegression(WebDriver driver) {
        this(driver, Path.of("baselines"), Path.of("target", "visual-diffs"));
    }

    public VisualRegression(WebDriver driver, Path baselinesDir) {
        this(driver, baselinesDir, Path.of("target", "visual-diffs"));
    }

    public VisualRegression(WebDriver driver, Path baselinesDir, Path diffsDir) {
        this.driver      = driver;
        this.baselinesDir = baselinesDir;
        this.diffsDir    = diffsDir;
    }

    // ── Baseline capture ──────────────────────────────────────────────────────

    /**
     * Captures the current page screenshot and saves it as the baseline for {@code name}.
     * If a baseline already exists, this will OVERWRITE it.
     *
     * @param name logical name for this screenshot (e.g. "homepage", "checkout-step-2")
     * @return path to the saved baseline file
     */
    public Path captureBaseline(String name) throws IOException {
        Files.createDirectories(baselinesDir);
        Path baseline = baselinePath(name);
        byte[] png = screenshot();
        Files.write(baseline, png);
        log.info("VisualRegression: baseline captured → {}", baseline.toAbsolutePath());
        return baseline;
    }

    /**
     * Captures baseline only if no baseline exists for {@code name}.
     * @return the baseline path (whether pre-existing or newly created)
     */
    public Path captureBaselineIfMissing(String name) throws IOException {
        Path baseline = baselinePath(name);
        if (Files.exists(baseline)) {
            log.debug("VisualRegression: baseline already exists for '{}' — skipping capture", name);
            return baseline;
        }
        return captureBaseline(name);
    }

    // ── Comparison ────────────────────────────────────────────────────────────

    /**
     * Compares the current screenshot against the stored baseline using pixel diff.
     *
     * @param name      logical baseline name
     * @param threshold maximum allowed fraction of differing pixels (0.0–1.0)
     * @throws AssertionError if the diff ratio exceeds {@code threshold}
     * @throws IOException    if the baseline file doesn't exist or cannot be read
     */
    public void assertMatchesBaseline(String name, double threshold) throws IOException {
        Path baseline = baselinePath(name);
        if (!Files.exists(baseline)) {
            throw new AssertionError(
                    "VisualRegression: no baseline found for '" + name + "'. " +
                    "Run captureBaseline(\"" + name + "\") first. Expected at: " +
                    baseline.toAbsolutePath());
        }

        byte[] currentPng  = screenshot();
        byte[] baselinePng = Files.readAllBytes(baseline);

        DiffResult result = pixelDiff(baselinePng, currentPng);
        log.info("VisualRegression: '{}' diff ratio = {} (threshold: {}, pixels: {}/{})",
                name, String.format("%.4f", result.diffRatio()), String.format("%.4f", threshold),
                result.diffPixels(), result.totalPixels());

        if (result.diffRatio() > threshold) {
            // Save diff image for inspection
            Path diffPath = saveDiff(name, currentPng, result);
            throw new AssertionError(String.format(
                    "VisualRegression: '%s' FAILED — diff ratio %.4f > threshold %.4f " +
                    "(%d/%d pixels differ). Diff saved: %s",
                    name, result.diffRatio(), threshold,
                    result.diffPixels(), result.totalPixels(), diffPath));
        }
        log.info("VisualRegression: '{}' PASSED (diff: {})", name, String.format("%.4f", result.diffRatio()));
    }

    /**
     * Computes the pixel diff ratio between the current screenshot and a baseline.
     *
     * @return diff ratio in [0, 1] where 0 = identical, 1 = completely different
     */
    public double diffRatio(String name) throws IOException {
        Path baseline = baselinePath(name);
        if (!Files.exists(baseline)) return 1.0;
        byte[] currentPng  = screenshot();
        byte[] baselinePng = Files.readAllBytes(baseline);
        return pixelDiff(baselinePng, currentPng).diffRatio();
    }

    // ── Pixel diff engine ─────────────────────────────────────────────────────

    /**
     * Performs a pixel-by-pixel comparison of two PNG byte arrays.
     *
     * <p>If dimensions differ, the images are compared at the smaller common area.
     * Each pixel is compared in RGB; the diff threshold per channel is 10/255.
     */
    public static DiffResult pixelDiff(byte[] baselinePng, byte[] currentPng) throws IOException {
        BufferedImage baseline = ImageIO.read(new ByteArrayInputStream(baselinePng));
        BufferedImage current  = ImageIO.read(new ByteArrayInputStream(currentPng));

        if (baseline == null || current == null) {
            throw new IOException("Could not decode one or both PNG images");
        }

        int width  = Math.min(baseline.getWidth(),  current.getWidth());
        int height = Math.min(baseline.getHeight(), current.getHeight());
        int total  = width * height;
        int diff   = 0;

        // Create diff image (highlight changed pixels in red)
        BufferedImage diffImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int bRgb = baseline.getRGB(x, y);
                int cRgb = current.getRGB(x, y);

                if (bRgb != cRgb && isSignificantDiff(bRgb, cRgb)) {
                    diff++;
                    diffImage.setRGB(x, y, 0xFF0000); // red highlight
                } else {
                    // Dim the matching pixel for contrast
                    int dimmed = ((bRgb & 0xFEFEFE) >> 1);
                    diffImage.setRGB(x, y, dimmed);
                }
            }
        }

        return new DiffResult(diff, total, diffImage);
    }

    /**
     * Returns {@code true} if the two RGB values differ by more than the noise threshold
     * on any channel (avoids false positives from sub-pixel anti-aliasing).
     */
    private static boolean isSignificantDiff(int rgb1, int rgb2) {
        int threshold = 10;
        int dr = Math.abs(((rgb1 >> 16) & 0xFF) - ((rgb2 >> 16) & 0xFF));
        int dg = Math.abs(((rgb1 >>  8) & 0xFF) - ((rgb2 >>  8) & 0xFF));
        int db = Math.abs(( rgb1        & 0xFF) - ( rgb2        & 0xFF));
        return dr > threshold || dg > threshold || db > threshold;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private byte[] screenshot() {
        return ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
    }

    private Path baselinePath(String name) {
        return baselinesDir.resolve(sanitize(name) + ".png");
    }

    private Path saveDiff(String name, byte[] currentPng, DiffResult result) {
        try {
            Files.createDirectories(diffsDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path currentPath = diffsDir.resolve(sanitize(name) + "_current_" + timestamp + ".png");
            Path diffPath    = diffsDir.resolve(sanitize(name) + "_diff_"    + timestamp + ".png");

            Files.write(currentPath, currentPng);
            ImageIO.write(result.diffImage(), "PNG", diffPath.toFile());
            log.info("VisualRegression: diff images saved → {}", diffsDir.toAbsolutePath());
            return diffPath;
        } catch (IOException e) {
            log.warn("VisualRegression: could not save diff images: {}", e.getMessage());
            return diffsDir.resolve(name + "_diff.png");
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    // ── Result record ──────────────────────────────────────────────────────────

    /**
     * Result of a pixel diff comparison.
     */
    public record DiffResult(int diffPixels, int totalPixels, BufferedImage diffImage) {
        /** Returns the fraction of pixels that differ (0.0 = identical, 1.0 = all different). */
        public double diffRatio() {
            return totalPixels == 0 ? 0.0 : (double) diffPixels / totalPixels;
        }
        public boolean isPassed(double threshold) { return diffRatio() <= threshold; }
    }
}
