package autoqa.player;

import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link VisualRegression} pixel diff engine.
 * No browser required.
 */
public class VisualRegressionTest {

    // ── Image helpers ─────────────────────────────────────────────────────────

    private static byte[] solidColorPng(int width, int height, Color color) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    private static byte[] whiteImage() throws IOException {
        return solidColorPng(100, 100, Color.WHITE);
    }

    private static byte[] blackImage() throws IOException {
        return solidColorPng(100, 100, Color.BLACK);
    }

    private static byte[] nearWhiteImage() throws IOException {
        return solidColorPng(100, 100, new Color(252, 252, 252)); // within noise threshold
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    public void identicalImages_zeroDiff() throws IOException {
        byte[] img = whiteImage();
        VisualRegression.DiffResult result = VisualRegression.pixelDiff(img, img);
        assertThat(result.diffPixels()).isEqualTo(0);
        assertThat(result.diffRatio()).isEqualTo(0.0);
        assertThat(result.isPassed(0.0)).isTrue();
    }

    @Test
    public void completelyDifferentImages_highDiff() throws IOException {
        VisualRegression.DiffResult result = VisualRegression.pixelDiff(whiteImage(), blackImage());
        // All pixels differ (white vs black = large channel difference)
        assertThat(result.diffRatio()).isGreaterThan(0.5);
        assertThat(result.isPassed(0.01)).isFalse();
    }

    @Test
    public void nearIdenticalImages_withinNoiseThreshold() throws IOException {
        // Color(252,252,252) vs Color(255,255,255) — difference of 3/255 < noise threshold of 10
        VisualRegression.DiffResult result = VisualRegression.pixelDiff(whiteImage(), nearWhiteImage());
        assertThat(result.diffPixels()).isEqualTo(0);
        assertThat(result.isPassed(0.0)).isTrue();
    }

    @Test
    public void diffRatio_isProportional() throws IOException {
        // Create image with exactly half of pixels changed
        int W = 100, H = 100;
        byte[] base = solidColorPng(W, H, Color.WHITE);

        BufferedImage mixed = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = mixed.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.RED);
        g.fillRect(0, 0, W / 2, H); // left half red
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(mixed, "PNG", baos);
        byte[] current = baos.toByteArray();

        VisualRegression.DiffResult result = VisualRegression.pixelDiff(base, current);
        // Approximately 50% of pixels should differ
        assertThat(result.diffRatio()).isBetween(0.45, 0.55);
    }

    @Test
    public void isPassed_checksThreshold() throws IOException {
        VisualRegression.DiffResult result = VisualRegression.pixelDiff(whiteImage(), blackImage());
        assertThat(result.isPassed(1.0)).isTrue();  // 100% threshold — always passes
        assertThat(result.isPassed(0.0)).isFalse(); // 0% threshold — impossible to pass if any diff
    }

    @Test
    public void defaultThreshold_isDefinedAndReasonable() {
        assertThat(VisualRegression.DEFAULT_THRESHOLD).isBetween(0.0, 0.10);
    }

    @Test
    public void diffImage_hasSameDimensionsAsInput() throws IOException {
        VisualRegression.DiffResult result = VisualRegression.pixelDiff(whiteImage(), blackImage());
        assertThat(result.diffImage().getWidth()).isEqualTo(100);
        assertThat(result.diffImage().getHeight()).isEqualTo(100);
    }
}
