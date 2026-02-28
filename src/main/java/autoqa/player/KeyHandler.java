package autoqa.player;

import autoqa.model.InputData;
import autoqa.model.RecordedEvent;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handles {@code KEY_PRESS} events (special keys and key chords with modifiers).
 *
 * <p>Strategy:
 * <ol>
 *   <li>Map {@code inputData.keyCode} to a {@link Keys} constant (with alias support).</li>
 *   <li>If the event has a target element: resolve it, wait for visibility, and send the
 *       key (or chord) to that element.</li>
 *   <li>If no element: send the key/chord to the currently active element via
 *       {@link Actions}.</li>
 * </ol>
 *
 * <p>Modifier strings supported: {@code CTRL / CONTROL}, {@code SHIFT}, {@code ALT},
 * {@code META / CMD / COMMAND / WIN}.
 */
public class KeyHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(KeyHandler.class);

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        InputData inputData = event.getInputData();
        if (inputData == null) {
            throw new AutoQAException("KEY_PRESS event has no inputData: " + event);
        }

        String keyCode = inputData.getKeyCode();
        if (keyCode == null || keyCode.isBlank()) {
            throw new AutoQAException("KEY_PRESS event has no keyCode: " + event);
        }

        Keys seleniumKey = resolveKey(keyCode);
        List<String> modifiers = inputData.getModifiers();
        boolean hasModifiers = modifiers != null && !modifiers.isEmpty();

        if (event.hasElement()) {
            WebElement element = HandlerSupport.resolveVisible(driver, event.getElement(), resolver, wait);

            if (hasModifiers) {
                CharSequence chord = buildChord(modifiers, seleniumKey);
                log.info("Sending key chord {} to element: {}",
                        describeChord(modifiers, keyCode), event.getElement());
                element.sendKeys(chord);
            } else {
                log.info("Sending key {} to element: {}", keyCode, event.getElement());
                element.sendKeys(seleniumKey);
            }

        } else {
            // No target element — send to active element via Actions
            Actions actions = new Actions(driver);
            if (hasModifiers) {
                log.info("Sending key chord {} to active element (no element specified)",
                        describeChord(modifiers, keyCode));
                applyModifiersDown(actions, modifiers);
                actions.sendKeys(seleniumKey);
                applyModifiersUp(actions, modifiers);
            } else {
                log.info("Sending key {} to active element (no element specified)", keyCode);
                actions.sendKeys(seleniumKey);
            }
            actions.perform();
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Maps a key-code string to a {@link Keys} constant.
     * First tries an exact {@link Keys#valueOf(String)} lookup, then common aliases.
     */
    private Keys resolveKey(String keyCode) {
        String upper = keyCode.toUpperCase();

        try {
            return Keys.valueOf(upper);
        } catch (IllegalArgumentException ignored) { }

        // Common aliases not present as Keys enum names
        return switch (upper) {
            case "RETURN"       -> Keys.RETURN;
            case "DEL"          -> Keys.DELETE;
            case "ESC"          -> Keys.ESCAPE;
            case "PAGEUP"       -> Keys.PAGE_UP;
            case "PAGEDOWN"     -> Keys.PAGE_DOWN;
            case "LEFTARROW"    -> Keys.ARROW_LEFT;
            case "RIGHTARROW"   -> Keys.ARROW_RIGHT;
            case "UPARROW"      -> Keys.ARROW_UP;
            case "DOWNARROW"    -> Keys.ARROW_DOWN;
            default -> throw new AutoQAException(
                    "Unrecognised keyCode '" + keyCode + "'. "
                    + "Use a Keys enum name (e.g. ENTER, TAB, ESCAPE, F5).");
        };
    }

    /** Builds a {@link Keys#chord(CharSequence...)} from modifier strings + the main key. */
    private CharSequence buildChord(List<String> modifiers, Keys mainKey) {
        CharSequence[] parts = new CharSequence[modifiers.size() + 1];
        for (int i = 0; i < modifiers.size(); i++) {
            parts[i] = modifierKey(modifiers.get(i));
        }
        parts[modifiers.size()] = mainKey;
        return Keys.chord(parts);
    }

    /** Presses each modifier key down for Actions-based chords without a target element. */
    private void applyModifiersDown(Actions actions, List<String> modifiers) {
        for (String mod : modifiers) {
            actions.keyDown(modifierKey(mod));
        }
    }

    /** Releases each modifier key for Actions-based chords without a target element. */
    private void applyModifiersUp(Actions actions, List<String> modifiers) {
        for (int i = modifiers.size() - 1; i >= 0; i--) {
            actions.keyUp(modifierKey(modifiers.get(i)));
        }
    }

    private Keys modifierKey(String modifier) {
        return switch (modifier.toUpperCase()) {
            case "CTRL", "CONTROL"              -> Keys.CONTROL;
            case "SHIFT"                        -> Keys.SHIFT;
            case "ALT"                          -> Keys.ALT;
            case "META", "CMD", "COMMAND", "WIN" -> Keys.META;
            default -> throw new AutoQAException(
                    "Unrecognised modifier key: '" + modifier + "'");
        };
    }

    private String describeChord(List<String> modifiers, String keyCode) {
        return String.join("+", modifiers) + "+" + keyCode;
    }
}
