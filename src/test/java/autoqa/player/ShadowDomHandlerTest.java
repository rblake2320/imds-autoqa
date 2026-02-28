package autoqa.player;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShadowDomHandler} (non-browser logic).
 */
public class ShadowDomHandlerTest {

    @Test
    public void isShadowLocator_detectsPrefix() {
        assertThat(ShadowDomHandler.isShadowLocator("shadow://my-app >>> button")).isTrue();
    }

    @Test
    public void isShadowLocator_detectsSeparatorWithoutPrefix() {
        assertThat(ShadowDomHandler.isShadowLocator("my-app >>> input")).isTrue();
    }

    @Test
    public void isShadowLocator_returnsFalseForRegularSelector() {
        assertThat(ShadowDomHandler.isShadowLocator("#submit-btn")).isFalse();
        assertThat(ShadowDomHandler.isShadowLocator("//button[@id='foo']")).isFalse();
    }

    @Test
    public void isShadowLocator_handlesNull() {
        assertThat(ShadowDomHandler.isShadowLocator(null)).isFalse();
    }

    @Test
    public void constants_haveExpectedValues() {
        assertThat(ShadowDomHandler.SHADOW_PREFIX).isEqualTo("shadow://");
        assertThat(ShadowDomHandler.SHADOW_SEPARATOR).isEqualTo(">>>");
    }
}
