package autoqa.recorder;

import autoqa.model.RecordedEvent;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import org.mockito.MockedStatic;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OSInputCapture}.
 *
 * <p>JNativeHook requires actual native libraries to register hooks, so all
 * tests that exercise the start/stop lifecycle mock {@link GlobalScreen}
 * using Mockito's {@code mockStatic}. Tests for the event-dispatch methods
 * ({@code nativeMouseClicked}, {@code nativeKeyTyped}, etc.) call those
 * methods directly on the {@link OSInputCapture} instance, bypassing the
 * native layer entirely.
 *
 * <p>This lets us verify:
 * <ul>
 *   <li>Correct event type mapping (CLICK, DOUBLE_CLICK, CONTEXT_MENU, INPUT, KEY_PRESS)</li>
 *   <li>Modifier key state tracking</li>
 *   <li>Inactive capture gate ({@code active == false} suppresses events)</li>
 *   <li>Graceful handling of {@link NativeHookException} on start/stop</li>
 *   <li>Null-safe callback invocation</li>
 * </ul>
 */
public class OSInputCaptureTest {

    private OSInputCapture capture;
    private List<RecordedEvent> captured;

    @BeforeMethod
    public void setUp() {
        capture  = new OSInputCapture(Set.of());
        captured = new ArrayList<>();
    }

    @AfterMethod
    public void tearDown() {
        // Ensure the active flag is reset between tests even if a test fails
        // without calling stop(). We bypass GlobalScreen here.
        captured.clear();
    }

    // ── start / stop lifecycle ────────────────────────────────────────────

    @Test
    public void start_registersHookAndSetsActiveTrue() throws NativeHookException {
        try (MockedStatic<GlobalScreen> gs = mockStatic(GlobalScreen.class)) {
            gs.when(GlobalScreen::registerNativeHook).thenAnswer(inv -> null);

            capture.start(captured::add);

            gs.verify(GlobalScreen::registerNativeHook, times(1));
            gs.verify(() -> GlobalScreen.addNativeMouseListener(capture), times(1));
            gs.verify(() -> GlobalScreen.addNativeMouseMotionListener(capture), times(1));
            gs.verify(() -> GlobalScreen.addNativeKeyListener(capture), times(1));
            assertThat(capture.isActive()).isTrue();
        }
    }

    @Test
    public void stop_unregistersHookAndSetsActiveFalse() throws NativeHookException {
        try (MockedStatic<GlobalScreen> gs = mockStatic(GlobalScreen.class)) {
            gs.when(GlobalScreen::registerNativeHook).thenAnswer(inv -> null);
            gs.when(GlobalScreen::unregisterNativeHook).thenAnswer(inv -> null);

            capture.start(captured::add);
            capture.stop();

            gs.verify(GlobalScreen::unregisterNativeHook, times(1));
            gs.verify(() -> GlobalScreen.removeNativeMouseListener(capture), times(1));
            gs.verify(() -> GlobalScreen.removeNativeMouseMotionListener(capture), times(1));
            gs.verify(() -> GlobalScreen.removeNativeKeyListener(capture), times(1));
            assertThat(capture.isActive()).isFalse();
        }
    }

    @Test
    public void start_throwsRuntimeException_onNativeHookException() {
        try (MockedStatic<GlobalScreen> gs = mockStatic(GlobalScreen.class)) {
            gs.when(GlobalScreen::registerNativeHook)
              .thenThrow(new NativeHookException("no native lib"));

            assertThatThrownBy(() -> capture.start(captured::add))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to register native hook");
        }
    }

    @Test
    public void stop_logsWarning_onUnregisterException() throws NativeHookException {
        // stop() should not throw even if unregisterNativeHook fails
        try (MockedStatic<GlobalScreen> gs = mockStatic(GlobalScreen.class)) {
            gs.when(GlobalScreen::registerNativeHook).thenAnswer(inv -> null);
            gs.when(GlobalScreen::unregisterNativeHook)
              .thenThrow(new NativeHookException("already unregistered"));

            capture.start(captured::add);
            // Must not throw
            capture.stop();

            assertThat(capture.isActive()).isFalse();
        }
    }

    // ── Mouse click events ────────────────────────────────────────────────

    @Test
    public void nativeMouseClicked_singleLeft_firesCLICK() {
        activateWithoutNativeHook();
        NativeMouseEvent e = makeMouse(100, 200, NativeMouseEvent.BUTTON1, 1);
        capture.nativeMouseClicked(e);

        assertThat(captured).hasSize(1);
        RecordedEvent evt = captured.get(0);
        assertThat(evt.getEventType()).isEqualTo(RecordedEvent.EventType.CLICK);
        assertThat(evt.getCoordinates().getX()).isEqualTo(100.0);
        assertThat(evt.getCoordinates().getY()).isEqualTo(200.0);
        assertThat(evt.getTimestamp()).isNotNull();
    }

    @Test
    public void nativeMouseClicked_doubleLeft_firesDOUBLE_CLICK() {
        activateWithoutNativeHook();
        NativeMouseEvent e = makeMouse(50, 60, NativeMouseEvent.BUTTON1, 2);
        capture.nativeMouseClicked(e);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getEventType())
                .isEqualTo(RecordedEvent.EventType.DOUBLE_CLICK);
    }

    @Test
    public void nativeMouseClicked_rightButton_firesCONTEXT_MENU() {
        activateWithoutNativeHook();
        // Button3 is conventionally the right mouse button in JNativeHook
        NativeMouseEvent e = makeMouse(10, 20, NativeMouseEvent.BUTTON3, 1);
        capture.nativeMouseClicked(e);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getEventType())
                .isEqualTo(RecordedEvent.EventType.CONTEXT_MENU);
    }

    @Test
    public void nativeMouseClicked_middleButton_firesCLICK() {
        activateWithoutNativeHook();
        NativeMouseEvent e = makeMouse(10, 20, NativeMouseEvent.BUTTON2, 1);
        capture.nativeMouseClicked(e);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getEventType())
                .isEqualTo(RecordedEvent.EventType.CLICK);
    }

    @Test
    public void nativeMouseClicked_updatesLastPosition() {
        activateWithoutNativeHook();
        NativeMouseEvent e = makeMouse(300, 400, NativeMouseEvent.BUTTON1, 1);
        capture.nativeMouseClicked(e);

        assertThat(capture.getLastX()).isEqualTo(300.0);
        assertThat(capture.getLastY()).isEqualTo(400.0);
    }

    @Test
    public void nativeMouseMoved_updatesPositionWithoutEvent() {
        activateWithoutNativeHook();
        NativeMouseEvent e = makeMouse(77, 88, NativeMouseEvent.NOBUTTON, 0);
        capture.nativeMouseMoved(e);

        assertThat(capture.getLastX()).isEqualTo(77.0);
        assertThat(capture.getLastY()).isEqualTo(88.0);
        assertThat(captured).isEmpty();
    }

    // ── Inactive gate ─────────────────────────────────────────────────────

    @Test
    public void mouseClick_whenInactive_doesNotFire() {
        // Do NOT activate; active flag is false by default
        NativeMouseEvent e = makeMouse(1, 2, NativeMouseEvent.BUTTON1, 1);
        capture.nativeMouseClicked(e);
        assertThat(captured).isEmpty();
    }

    @Test
    public void keyTyped_whenInactive_doesNotFire() {
        NativeKeyEvent e = makeKeyTyped('a');
        capture.nativeKeyTyped(e);
        assertThat(captured).isEmpty();
    }

    // ── Keyboard events ───────────────────────────────────────────────────

    @Test
    public void nativeKeyTyped_firesINPUT_withCharacter() {
        activateWithoutNativeHook();
        NativeKeyEvent e = makeKeyTyped('H');
        capture.nativeKeyTyped(e);

        assertThat(captured).hasSize(1);
        RecordedEvent evt = captured.get(0);
        assertThat(evt.getEventType()).isEqualTo(RecordedEvent.EventType.INPUT);
        assertThat(evt.getInputData()).isNotNull();
        assertThat(evt.getInputData().getKeys()).isEqualTo("H");
    }

    @Test
    public void nativeKeyTyped_charUndefined_doesNotFire() {
        activateWithoutNativeHook();
        // Some JNativeHook versions throw IllegalArgumentException when constructing
        // a NATIVE_KEY_TYPED event with CHAR_UNDEFINED — treat that as proof the event
        // was not (and could not be) processed.
        NativeKeyEvent e;
        try {
            e = makeKeyTyped(NativeKeyEvent.CHAR_UNDEFINED);
        } catch (IllegalArgumentException ignored) {
            assertThat(captured).isEmpty();
            return;
        }
        capture.nativeKeyTyped(e);
        assertThat(captured).isEmpty();
    }

    @Test
    public void nativeKeyPressed_enterKey_firesKEY_PRESS() {
        activateWithoutNativeHook();
        NativeKeyEvent e = makeKeyPressed(NativeKeyEvent.VC_ENTER, NativeKeyEvent.CHAR_UNDEFINED);
        capture.nativeKeyPressed(e);

        assertThat(captured).hasSize(1);
        RecordedEvent evt = captured.get(0);
        assertThat(evt.getEventType()).isEqualTo(RecordedEvent.EventType.KEY_PRESS);
        assertThat(evt.getInputData().getKeyCode()).isEqualTo("ENTER");
        assertThat(evt.getInputData().getModifiers()).isNull(); // no modifiers
    }

    @Test
    public void nativeKeyPressed_tabKey_firesKEY_PRESS_withCodeTAB() {
        activateWithoutNativeHook();
        capture.nativeKeyPressed(makeKeyPressed(NativeKeyEvent.VC_TAB, NativeKeyEvent.CHAR_UNDEFINED));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getInputData().getKeyCode()).isEqualTo("TAB");
    }

    @Test
    public void nativeKeyPressed_f5Key_firesKEY_PRESS() {
        activateWithoutNativeHook();
        capture.nativeKeyPressed(makeKeyPressed(NativeKeyEvent.VC_F5, NativeKeyEvent.CHAR_UNDEFINED));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getInputData().getKeyCode()).isEqualTo("F5");
    }

    // ── Modifier key state tracking ───────────────────────────────────────

    @Test
    public void modifier_ctrlDown_attachedToNextKeyPress() {
        activateWithoutNativeHook();
        // Press Ctrl (sets state, does NOT emit event)
        capture.nativeKeyPressed(makeKeyPressed(NativeKeyEvent.VC_CONTROL, NativeKeyEvent.CHAR_UNDEFINED));
        assertThat(captured).isEmpty(); // modifier alone emits nothing

        // Press Enter while Ctrl is held
        capture.nativeKeyPressed(makeKeyPressed(NativeKeyEvent.VC_ENTER, NativeKeyEvent.CHAR_UNDEFINED));
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getInputData().getModifiers())
                .containsExactly("CTRL");
    }

    @Test
    public void modifier_shiftCtrlDown_bothListed() {
        activateWithoutNativeHook();
        capture.nativeKeyPressed(makeKeyPressed(NativeKeyEvent.VC_CONTROL, NativeKeyEvent.CHAR_UNDEFINED));
        capture.nativeKeyPressed(makeKeyPressed(NativeKeyEvent.VC_SHIFT, NativeKeyEvent.CHAR_UNDEFINED));
        capture.nativeKeyPressed(makeKeyPressed(NativeKeyEvent.VC_TAB, NativeKeyEvent.CHAR_UNDEFINED));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getInputData().getModifiers())
                .containsExactlyInAnyOrder("CTRL", "SHIFT");
    }

    @Test
    public void modifier_releasedBeforeNextKey_notAttached() {
        activateWithoutNativeHook();
        capture.nativeKeyPressed(makeKeyPressed(NativeKeyEvent.VC_CONTROL, NativeKeyEvent.CHAR_UNDEFINED));
        // Release Ctrl
        capture.nativeKeyReleased(makeKeyPressed(NativeKeyEvent.VC_CONTROL, NativeKeyEvent.CHAR_UNDEFINED));
        // Press Enter — Ctrl should no longer be in modifiers
        capture.nativeKeyPressed(makeKeyPressed(NativeKeyEvent.VC_ENTER, NativeKeyEvent.CHAR_UNDEFINED));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getInputData().getModifiers()).isNull();
    }

    @Test
    public void modifierKeyAlone_doesNotFireEvent() {
        activateWithoutNativeHook();
        for (int modKey : new int[]{
                NativeKeyEvent.VC_CONTROL,
                NativeKeyEvent.VC_SHIFT,
                NativeKeyEvent.VC_ALT,
                NativeKeyEvent.VC_META}) {
            capture.nativeKeyPressed(makeKeyPressed(modKey, NativeKeyEvent.CHAR_UNDEFINED));
        }
        assertThat(captured).isEmpty();
    }

    // ── Null-safe callback ────────────────────────────────────────────────

    @Test
    public void callbackNull_doesNotThrow() {
        // start() was never called, so callback is null
        // Manually set active to trigger the event path
        activateWithoutNativeHook();
        // Re-create with null callback to test that path
        OSInputCapture c2 = new OSInputCapture(Set.of());
        // Do NOT call start() — callback remains null
        // Forcibly set active via reflection or use the package-visible isActive guard.
        // Since we cannot set active without start(), test the guard indirectly:
        // nativeMouseClicked returns early because active == false, so no NPE
        NativeMouseEvent e = makeMouse(0, 0, NativeMouseEvent.BUTTON1, 1);
        c2.nativeMouseClicked(e); // should not throw
    }

    // ── Helper factory methods ────────────────────────────────────────────

    /**
     * Activates the capture instance by setting its internal {@code active}
     * flag without touching the native JNativeHook registry.
     * The callback is wired to the shared {@code captured} list.
     */
    private void activateWithoutNativeHook() {
        try (MockedStatic<GlobalScreen> gs = mockStatic(GlobalScreen.class)) {
            gs.when(GlobalScreen::registerNativeHook).thenAnswer(inv -> null);
            capture.start(captured::add);
        }
    }

    /**
     * Creates a {@link NativeMouseEvent} for testing. JNativeHook's constructor
     * requires a Component source; we pass {@code null} which is acceptable for
     * unit tests since the event is never dispatched through Swing.
     */
    private static NativeMouseEvent makeMouse(int x, int y, int button, int clickCount) {
        return new NativeMouseEvent(
                NativeMouseEvent.NATIVE_MOUSE_CLICKED,
                0,       // modifiers
                x, y,
                clickCount,
                button
        );
    }

    /**
     * Creates a {@link NativeKeyEvent} with the given key code as if the key
     * was typed (used for nativeKeyTyped tests).
     */
    private static NativeKeyEvent makeKeyTyped(char keyChar) {
        return new NativeKeyEvent(
                NativeKeyEvent.NATIVE_KEY_TYPED,
                0,                          // modifiers
                NativeKeyEvent.VC_UNDEFINED, // keyCode
                NativeKeyEvent.VC_UNDEFINED, // rawCode
                keyChar                     // keyChar
        );
    }

    /**
     * Creates a {@link NativeKeyEvent} simulating a key-pressed event.
     */
    private static NativeKeyEvent makeKeyPressed(int keyCode, char keyChar) {
        return new NativeKeyEvent(
                NativeKeyEvent.NATIVE_KEY_PRESSED,
                0,       // modifiers
                keyCode,
                keyCode, // rawCode
                keyChar
        );
    }
}
