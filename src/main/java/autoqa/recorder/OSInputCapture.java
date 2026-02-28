package autoqa.recorder;

import autoqa.model.Coordinates;
import autoqa.model.InputData;
import autoqa.model.RecordedEvent;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseMotionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Captures global OS mouse + keyboard events using JNativeHook.
 * Filters to events while msedge.exe is the foreground process.
 * Implements {@link InputCaptureAdapter} for use by RecordingSession.
 *
 * <p>Mouse click coordinates are captured in screen space; DOMEnricher
 * (Agent A component) is responsible for translating them to DOM element
 * info via the CDP debug protocol.
 *
 * <p>Modifier key state (Ctrl, Shift, Alt, Meta) is tracked and attached
 * to KEY_PRESS events. Printable characters are delivered via
 * {@code nativeKeyTyped} as INPUT events to avoid duplication.
 */
public class OSInputCapture implements InputCaptureAdapter,
        NativeMouseListener, NativeMouseMotionListener, NativeKeyListener {

    private static final Logger log = LoggerFactory.getLogger(OSInputCapture.class);

    private Consumer<RecordedEvent> callback;
    private final AtomicBoolean active = new AtomicBoolean(false);

    /**
     * Optional URL whitelist — kept here for future filtering when the
     * foreground URL is known via CDP. Currently unused at capture time;
     * DOMEnricher applies the filter during enrichment.
     */
    private final Set<String> urlWhitelist;

    // ── Modifier key state ────────────────────────────────────────────────

    private boolean ctrlDown  = false;
    private boolean shiftDown = false;
    private boolean altDown   = false;
    private boolean metaDown  = false;

    // ── Last known cursor position (updated on every mouse move / click) ──

    private double lastX = 0;
    private double lastY = 0;

    // ─────────────────────────────────────────────────────────────────────

    public OSInputCapture(Set<String> urlWhitelist) {
        this.urlWhitelist = urlWhitelist;
    }

    // ── InputCaptureAdapter ───────────────────────────────────────────────

    @Override
    public void start(Consumer<RecordedEvent> eventCallback) {
        this.callback = eventCallback;
        try {
            // Suppress JNativeHook's verbose console output; SLF4J handles our logging.
            java.util.logging.Logger nativeLogger =
                    java.util.logging.Logger.getLogger(
                            GlobalScreen.class.getPackage().getName());
            nativeLogger.setLevel(java.util.logging.Level.WARNING);
            nativeLogger.setUseParentHandlers(false);

            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeMouseListener(this);
            GlobalScreen.addNativeMouseMotionListener(this);
            GlobalScreen.addNativeKeyListener(this);
            active.set(true);
            log.info("OS input capture started");
        } catch (NativeHookException e) {
            throw new RuntimeException("Failed to register native hook", e);
        }
    }

    @Override
    public void stop() {
        active.set(false);
        GlobalScreen.removeNativeMouseListener(this);
        GlobalScreen.removeNativeMouseMotionListener(this);
        GlobalScreen.removeNativeKeyListener(this);
        try {
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException e) {
            log.warn("Error unregistering native hook: {}", e.getMessage());
        }
        log.info("OS input capture stopped");
    }

    // ── NativeMouseListener ───────────────────────────────────────────────

    @Override
    public void nativeMouseClicked(NativeMouseEvent e) {
        if (!active.get()) return;

        RecordedEvent event = new RecordedEvent();
        event.setTimestamp(Instant.now());
        event.setCoordinates(new Coordinates((double) e.getX(), (double) e.getY()));

        if (e.getClickCount() == 2) {
            event.setEventType(RecordedEvent.EventType.DOUBLE_CLICK);
        } else if (e.getButton() == NativeMouseEvent.BUTTON3) {
            event.setEventType(RecordedEvent.EventType.CONTEXT_MENU);
        } else {
            event.setEventType(RecordedEvent.EventType.CLICK);
        }

        lastX = e.getX();
        lastY = e.getY();
        fireEvent(event);
    }

    /**
     * Tracks cursor position for use by DOMEnricher but does not produce
     * a recorded event on its own — recording hover events for every pixel
     * of mouse movement would be prohibitively verbose.
     */
    @Override
    public void nativeMouseMoved(NativeMouseEvent e) {
        lastX = e.getX();
        lastY = e.getY();
    }

    /** Drag operations are not recorded in this version. */
    @Override
    public void nativeMouseDragged(NativeMouseEvent e) {
        // not recorded
    }

    /** Mouse-down handled via nativeMouseClicked to get click-count. */
    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        // handled in nativeMouseClicked
    }

    /** Mouse-up handled via nativeMouseClicked to get click-count. */
    @Override
    public void nativeMouseReleased(NativeMouseEvent e) {
        // handled in nativeMouseClicked
    }

    // ── NativeKeyListener ─────────────────────────────────────────────────

    /**
     * Records special/function key presses (Enter, Tab, F-keys, etc.) as
     * KEY_PRESS events. Modifier-only presses are tracked in state but not
     * recorded as standalone events. Printable characters are handled by
     * {@link #nativeKeyTyped(NativeKeyEvent)}.
     */
    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        if (!active.get()) return;
        updateModifiers(e, true);

        String keyName = mapKeyCode(e.getKeyCode());
        if (keyName == null) return; // modifier-only or printable — skip here

        RecordedEvent event = new RecordedEvent();
        event.setTimestamp(Instant.now());
        event.setEventType(RecordedEvent.EventType.KEY_PRESS);

        InputData input = new InputData();
        input.setKeyCode(keyName);
        input.setModifiers(activeModifiers());
        event.setInputData(input);

        fireEvent(event);
    }

    /** Updates modifier state on release; no event is recorded. */
    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        updateModifiers(e, false);
    }

    /**
     * Records each printable character as an INPUT event.
     * The player coalesces consecutive INPUT events on the same element
     * into a single {@code sendKeys()} call.
     */
    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        if (!active.get()) return;
        char c = e.getKeyChar();
        if (c == NativeKeyEvent.CHAR_UNDEFINED) return;

        RecordedEvent event = new RecordedEvent();
        event.setTimestamp(Instant.now());
        event.setEventType(RecordedEvent.EventType.INPUT);

        InputData input = new InputData();
        input.setKeys(String.valueOf(c));
        event.setInputData(input);

        fireEvent(event);
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private void fireEvent(RecordedEvent event) {
        if (callback != null) {
            try {
                callback.accept(event);
            } catch (Exception ex) {
                log.warn("Event callback error: {}", ex.getMessage());
            }
        }
    }

    private void updateModifiers(NativeKeyEvent e, boolean pressed) {
        int code = e.getKeyCode();
        if (code == NativeKeyEvent.VC_CONTROL)    ctrlDown  = pressed;
        else if (code == NativeKeyEvent.VC_SHIFT) shiftDown = pressed;
        else if (code == NativeKeyEvent.VC_ALT)   altDown   = pressed;
        else if (code == NativeKeyEvent.VC_META)  metaDown  = pressed;
    }

    /**
     * Returns the currently held modifier keys as a list of string names,
     * or {@code null} if no modifiers are active (keeps the JSON compact).
     */
    private List<String> activeModifiers() {
        List<String> mods = new ArrayList<>();
        if (ctrlDown)  mods.add("CTRL");
        if (shiftDown) mods.add("SHIFT");
        if (altDown)   mods.add("ALT");
        if (metaDown)  mods.add("META");
        return mods.isEmpty() ? null : mods;
    }

    /**
     * Maps JNativeHook virtual-key codes to Selenium {@code Keys} enum names.
     *
     * <p>Returns {@code null} for:
     * <ul>
     *   <li>Modifier keys (Ctrl, Shift, Alt, Meta) — tracked separately</li>
     *   <li>Printable characters — handled by {@link #nativeKeyTyped}</li>
     * </ul>
     *
     * @param code JNativeHook key code from {@link NativeKeyEvent#getKeyCode()}
     * @return Selenium Keys name, or {@code null} if this key should not be
     *         recorded as a standalone KEY_PRESS event
     */
    private String mapKeyCode(int code) {
        return switch (code) {
            case NativeKeyEvent.VC_ENTER     -> "ENTER";
            case NativeKeyEvent.VC_TAB       -> "TAB";
            case NativeKeyEvent.VC_ESCAPE    -> "ESCAPE";
            case NativeKeyEvent.VC_BACKSPACE -> "BACK_SPACE";
            case NativeKeyEvent.VC_DELETE    -> "DELETE";
            case NativeKeyEvent.VC_HOME      -> "HOME";
            case NativeKeyEvent.VC_END       -> "END";
            case NativeKeyEvent.VC_PAGE_UP   -> "PAGE_UP";
            case NativeKeyEvent.VC_PAGE_DOWN -> "PAGE_DOWN";
            case NativeKeyEvent.VC_UP        -> "ARROW_UP";
            case NativeKeyEvent.VC_DOWN      -> "ARROW_DOWN";
            case NativeKeyEvent.VC_LEFT      -> "ARROW_LEFT";
            case NativeKeyEvent.VC_RIGHT     -> "ARROW_RIGHT";
            case NativeKeyEvent.VC_F1        -> "F1";
            case NativeKeyEvent.VC_F2        -> "F2";
            case NativeKeyEvent.VC_F3        -> "F3";
            case NativeKeyEvent.VC_F4        -> "F4";
            case NativeKeyEvent.VC_F5        -> "F5";
            case NativeKeyEvent.VC_F12       -> "F12";
            // Modifier keys — state tracked, but not recorded as KEY_PRESS
            case NativeKeyEvent.VC_CONTROL,
                 NativeKeyEvent.VC_SHIFT,
                 NativeKeyEvent.VC_ALT,
                 NativeKeyEvent.VC_META      -> null;
            // All other key codes are printable; handled by nativeKeyTyped
            default                          -> null;
        };
    }

    // ── Package-visible accessors for tests ───────────────────────────────

    /** Returns the last known X screen coordinate of the mouse cursor. */
    double getLastX() { return lastX; }

    /** Returns the last known Y screen coordinate of the mouse cursor. */
    double getLastY() { return lastY; }

    /** Returns {@code true} if capture is currently active. */
    boolean isActive() { return active.get(); }
}
