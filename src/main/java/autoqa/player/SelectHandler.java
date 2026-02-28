package autoqa.player;

import autoqa.model.ElementInfo;
import autoqa.model.InputData;
import autoqa.model.RecordedEvent;
import autoqa.model.SelectedOption;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handles {@code SELECT} events for both native {@code <select>} elements and
 * custom (div/ul/li-based) dropdowns.
 *
 * <p>For native {@code <select>}:
 * <ol>
 *   <li>Try {@link Select#selectByVisibleText(String)} if {@code selectedOption.text} is set.</li>
 *   <li>Fall back to {@link Select#selectByValue(String)} if {@code selectedOption.value} is set.</li>
 *   <li>Fall back to {@link Select#selectByIndex(int)} if {@code selectedOption.index} is set.</li>
 * </ol>
 *
 * <p>For custom dropdowns (tag is not {@code select}):
 * <ol>
 *   <li>Click the container to open it.</li>
 *   <li>Locate an option element whose visible text matches {@code selectedOption.text}.</li>
 *   <li>Click the option.</li>
 * </ol>
 */
public class SelectHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(SelectHandler.class);

    /** XPath templates tried when hunting for an option inside a custom dropdown. */
    private static final List<String> OPTION_XPATHS = List.of(
            ".//li[normalize-space(text())='%s']",
            ".//option[normalize-space(text())='%s']",
            ".//*[contains(@class,'option') and normalize-space(text())='%s']",
            ".//*[@role='option' and normalize-space(text())='%s']"
    );

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        ElementInfo ei = HandlerSupport.requireElement(event, "SELECT");

        InputData inputData = event.getInputData();
        if (inputData == null) {
            throw new AutoQAException("SELECT event has no inputData: " + event);
        }
        SelectedOption option = inputData.getSelectedOption();
        if (option == null) {
            throw new AutoQAException("SELECT event inputData.selectedOption is null: " + event);
        }

        WebElement element = HandlerSupport.resolvePresent(driver, ei, resolver, wait);

        if ("select".equalsIgnoreCase(element.getTagName())) {
            handleNativeSelect(element, option, ei);
        } else {
            handleCustomDropdown(driver, element, option, ei);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private void handleNativeSelect(WebElement element, SelectedOption option, ElementInfo ei) {
        Select select = new Select(element);
        boolean selected = false;

        if (!selected && option.getText() != null && !option.getText().isBlank()) {
            try {
                log.info("Selecting by visible text '{}' in: {}", option.getText(), ei);
                select.selectByVisibleText(option.getText());
                selected = true;
            } catch (Exception e) {
                log.debug("selectByVisibleText('{}') failed: {} — trying next strategy",
                        option.getText(), e.getMessage());
            }
        }

        if (!selected && option.getValue() != null && !option.getValue().isBlank()) {
            try {
                log.info("Selecting by value '{}' in: {}", option.getValue(), ei);
                select.selectByValue(option.getValue());
                selected = true;
            } catch (Exception e) {
                log.debug("selectByValue('{}') failed: {} — trying next strategy",
                        option.getValue(), e.getMessage());
            }
        }

        if (!selected && option.getIndex() != null) {
            try {
                log.info("Selecting by index {} in: {}", option.getIndex(), ei);
                select.selectByIndex(option.getIndex());
                selected = true;
            } catch (Exception e) {
                log.debug("selectByIndex({}) failed: {}", option.getIndex(), e.getMessage());
            }
        }

        if (!selected) {
            throw new AutoQAException(
                    "Could not select option " + option + " in " + ei
                    + " — all strategies (text, value, index) exhausted.");
        }
    }

    private void handleCustomDropdown(WebDriver driver, WebElement container,
                                      SelectedOption option, ElementInfo ei) {
        String targetText = option.getText();
        if (targetText == null || targetText.isBlank()) {
            throw new AutoQAException(
                    "Custom dropdown SELECT requires selectedOption.text but it is blank for: " + ei);
        }

        log.info("Custom dropdown: clicking container to open it: {}", ei);
        container.click();

        WebElement optionEl = findOptionByText(driver, container, targetText);
        if (optionEl == null) {
            throw new AutoQAException(
                    "Custom dropdown: option with text '" + targetText + "' not found after opening: " + ei);
        }

        log.info("Custom dropdown: clicking option '{}' in: {}", targetText, ei);
        optionEl.click();
    }

    /**
     * Searches for the option element first within the container, then page-wide
     * (for portalled/detached dropdowns that render outside the container in the DOM).
     */
    private WebElement findOptionByText(WebDriver driver, WebElement container, String text) {
        String escaped = text.replace("'", "\\'");

        // Scoped search within container
        for (String template : OPTION_XPATHS) {
            String xpath = String.format(template, escaped);
            try {
                List<WebElement> matches = container.findElements(By.xpath(xpath));
                for (WebElement el : matches) {
                    if (el.isDisplayed()) return el;
                }
            } catch (Exception ignored) { }
        }

        // Page-wide fallback for portalled / detached dropdowns
        for (String template : OPTION_XPATHS) {
            String xpath = String.format(template, escaped);
            try {
                List<WebElement> matches = driver.findElements(By.xpath(xpath));
                for (WebElement el : matches) {
                    if (el.isDisplayed()) return el;
                }
            } catch (Exception ignored) { }
        }

        return null;
    }
}
