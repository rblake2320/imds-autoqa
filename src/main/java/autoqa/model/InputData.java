package autoqa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Data associated with keyboard and dialog interactions.
 * Populated only for INPUT, KEY_PRESS, SELECT, and ALERT event types.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InputData {

    /** Text typed into a field. May be "[REDACTED]" for password fields. */
    @JsonProperty("keys")
    private String keys;

    /** Named key code, e.g. ENTER, TAB, ESCAPE, F5 (for KEY_PRESS events). */
    @JsonProperty("keyCode")
    private String keyCode;

    /** Modifier keys held during the key event. */
    @JsonProperty("modifiers")
    private List<String> modifiers;

    /** The option selected in a {@code <select>} dropdown (for SELECT events). */
    @JsonProperty("selectedOption")
    private SelectedOption selectedOption;

    /** How to handle an alert dialog (for ALERT events). */
    @JsonProperty("alertAction")
    private AlertAction alertAction;

    /** Text to type into a JavaScript prompt dialog. */
    @JsonProperty("alertText")
    private String alertText;

    public enum AlertAction { ACCEPT, DISMISS, SEND_KEYS }

    public InputData() {}

    // ── Getters ──────────────────────────────────────────────────────────

    public String         getKeys()           { return keys; }
    public String         getKeyCode()        { return keyCode; }
    public List<String>   getModifiers()      { return modifiers; }
    public SelectedOption getSelectedOption() { return selectedOption; }
    public AlertAction    getAlertAction()    { return alertAction; }
    public String         getAlertText()      { return alertText; }

    // ── Setters ──────────────────────────────────────────────────────────

    public void setKeys(String keys)                     { this.keys = keys; }
    public void setKeyCode(String keyCode)               { this.keyCode = keyCode; }
    public void setModifiers(List<String> modifiers)     { this.modifiers = modifiers; }
    public void setSelectedOption(SelectedOption opt)    { this.selectedOption = opt; }
    public void setAlertAction(AlertAction alertAction)  { this.alertAction = alertAction; }
    public void setAlertText(String alertText)           { this.alertText = alertText; }

    /** Convenience factory — typed text. */
    public static InputData ofKeys(String text) {
        InputData d = new InputData();
        d.keys = text;
        return d;
    }

    /** Convenience factory — special key press. */
    public static InputData ofKey(String keyCode) {
        InputData d = new InputData();
        d.keyCode = keyCode;
        return d;
    }

    /** Convenience factory — select option by visible text. */
    public static InputData ofSelect(String optionText) {
        InputData d = new InputData();
        d.selectedOption = new SelectedOption(optionText, null, null);
        return d;
    }
}
