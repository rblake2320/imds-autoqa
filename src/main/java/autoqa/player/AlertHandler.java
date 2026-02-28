package autoqa.player;

import autoqa.model.InputData;
import autoqa.model.RecordedEvent;
import org.openqa.selenium.Alert;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code ALERT} events (native JavaScript alert / confirm / prompt dialogs).
 *
 * <p>Dispatches on {@link InputData.AlertAction}:
 * <ul>
 *   <li>{@code ACCEPT}    — {@link Alert#accept()}.</li>
 *   <li>{@code DISMISS}   — {@link Alert#dismiss()}.</li>
 *   <li>{@code SEND_KEYS} — type {@link InputData#getAlertText()} into the prompt,
 *                           then accept.</li>
 * </ul>
 */
public class AlertHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertHandler.class);

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        InputData inputData = event.getInputData();
        if (inputData == null) {
            throw new AutoQAException("ALERT event has no inputData: " + event);
        }

        InputData.AlertAction action = inputData.getAlertAction();
        if (action == null) {
            throw new AutoQAException("ALERT event inputData.alertAction is null: " + event);
        }

        log.info("Waiting for alert (action={})", action);
        Alert alert = wait.waitForAlertPresent();

        String alertText = safeGetAlertText(alert);
        log.info("Alert present — text: '{}', performing action: {}", alertText, action);

        switch (action) {
            case ACCEPT -> {
                log.info("Accepting alert");
                alert.accept();
            }
            case DISMISS -> {
                log.info("Dismissing alert");
                alert.dismiss();
            }
            case SEND_KEYS -> {
                String textToSend = inputData.getAlertText();
                if (textToSend == null) {
                    throw new AutoQAException(
                            "ALERT SEND_KEYS requires alertText but it is null: " + event);
                }
                log.info("Sending keys to alert prompt, then accepting");
                alert.sendKeys(textToSend);
                alert.accept();
            }
            default -> throw new AutoQAException("Unhandled AlertAction: " + action);
        }
    }

    private String safeGetAlertText(Alert alert) {
        try {
            return alert.getText();
        } catch (Exception e) {
            return "(unable to read alert text: " + e.getMessage() + ")";
        }
    }
}
