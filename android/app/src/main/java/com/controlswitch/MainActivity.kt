package com.controlswitch

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.controlswitch.domain.model.AppTheme
import com.controlswitch.ui.MirrorScreen
import com.controlswitch.ui.MirrorViewModel
import com.controlswitch.ui.theme.DockingEnhancerTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    // The Compose screen registers how to anchor gamepad focus. We invoke it once the window gains
    // focus because FocusRequester.requestFocus() silently no-ops while the window is unfocused —
    // which is why focus never landed on launch until a button press handed it out by default.
    var onWindowFocused: (() -> Unit)? = null

    // Set while the Player 1 picker is open; called with the pad that pressed L + R.
    var onLPlusR: ((InputDevice) -> Unit)? = null
    private val shouldersHeld = mutableMapOf<Int, Int>()  // deviceId -> bits: 1 = L, 2 = R

    // Set while the mapping editor listens for a press in the app (mirror off). Asked first; true
    // swallows the event.
    var onPadCapture: ((KeyEvent) -> Boolean)? = null
    var onPadMotion: ((MotionEvent) -> Boolean)? = null

    private val handler = Handler(Looper.getMainLooper())

    private var hatDirection = 0
    private val hatRepeat = object : Runnable {
        override fun run() {
            if (hatDirection != 0) {
                sendDpadTap(hatDirection)
                handler.postDelayed(this, HAT_REPEAT_MS)
            }
        }
    }

    // Asked for once, never insisted on. A denial costs the status notification and nothing else —
    // the supervisor and the mirror run either way — so there is no prompt to re-raise and no
    // reason to block the UI on the answer.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A console's screen, not a phone's: the clock and notification icons along the top go. A
        // swipe down from the top edge still shows them for a moment.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        requestNotificationPermissionIfNeeded()

        setContent {
            val vm: MirrorViewModel = viewModel(factory = MirrorViewModel.factory(applicationContext))
            val theme = vm.state.collectAsState().value.theme
            // Status and navigation bar icons follow the app's theme, not the system's.
            LaunchedEffect(theme) {
                enableEdgeToEdge(
                    if (theme == AppTheme.LIGHT) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    else SystemBarStyle.dark(Color.TRANSPARENT),
                )
            }
            DockingEnhancerTheme(theme) {
                MirrorScreen(viewModel = vm)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Lint reads overriding dispatchKeyEvent as calling a restricted androidx API; it is the
    // platform Activity method, overridden, and the androidx restriction does not apply.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (onPadCapture?.invoke(event) == true) return true
        val picker = onLPlusR
        val bit = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> 1
            KeyEvent.KEYCODE_BUTTON_R1 -> 2
            else -> 0
        }
        if (picker == null || bit == 0) {
            return super.dispatchKeyEvent(event)
        }
        // Tracked per device so L on one pad and R on another never count as a press.
        val held = shouldersHeld[event.deviceId] ?: 0
        val now = if (event.action == KeyEvent.ACTION_DOWN) held or bit else held and bit.inv()
        shouldersHeld[event.deviceId] = now
        if (now == 3 && held != 3) {
            event.device?.let(picker)
        }
        return true
    }

    // Make the app fully gamepad-drivable, the way a handheld user expects:
    //  - The LEFT analog stick moves focus between the interactive controls (push it in any of the
    //    four directions to walk through the cards, switches, and button). Compose auto-scrolls the
    //    focused control into view, so focus navigation also handles most of the scrolling.
    //  - The D-pad (reported as hat axes by most controllers) does the same thing, so either input
    //    works. Both are translated into D-pad key events that Compose's focus system understands.
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (onPadMotion?.invoke(event) == true) return true
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            // Not while capturing: a stick push there is a binding, not a focus move.
            if (onPadMotion == null) handleFocusNav(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // Drive focus from whichever directional input is engaged — the D-pad hat or the left stick
    // pushed past its (larger) travel threshold. A direction only fires once per "push": it taps
    // immediately, then auto-repeats while held, and resets when the input returns to center.
    private fun handleFocusNav(event: MotionEvent) {
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val leftX = event.getAxisValue(MotionEvent.AXIS_X)
        val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        // Prefer the hat when it is engaged; otherwise read the left stick past its threshold.
        val x = if (abs(hatX) >= 0.5f) hatX else leftX.takeIf { abs(it) >= STICK_FOCUS_THRESHOLD } ?: 0f
        val y = if (abs(hatY) >= 0.5f) hatY else leftY.takeIf { abs(it) >= STICK_FOCUS_THRESHOLD } ?: 0f
        val direction = when {
            y <= -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            y >= 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            x <= -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            x >= 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> 0
        }
        if (direction == hatDirection) return
        handler.removeCallbacks(hatRepeat)
        hatDirection = direction
        if (direction != 0) {
            sendDpadTap(direction)
            handler.postDelayed(hatRepeat, HAT_INITIAL_MS)
        }
    }

    private fun sendDpadTap(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) onWindowFocused?.invoke()
    }

    // Tell the supervisor whether the device list is being watched. Visible (started) → it keeps
    // enumerating controllers so the UI stays live; hidden (stopped) → undocked, it can skip that
    // privileged PServer call. onStart/onStop (not onResume/onPause) so a transient dialog or the
    // recents overlay doesn't blank the list.
    override fun onStart() {
        super.onStart()
        MirrorStateStore.setUiVisible(true)
    }

    override fun onStop() {
        super.onStop()
        MirrorStateStore.setUiVisible(false)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(hatRepeat)
        hatDirection = 0
    }

    companion object {
        // A firm push, so a resting/drifting stick can't skip focus between controls.
        private const val STICK_FOCUS_THRESHOLD = 0.6f
        private const val HAT_INITIAL_MS = 350L
        private const val HAT_REPEAT_MS = 140L
    }
}
