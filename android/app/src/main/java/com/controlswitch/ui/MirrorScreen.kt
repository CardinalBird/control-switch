package com.controlswitch.ui

import com.controlswitch.domain.model.SlotIcon
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controlswitch.MainActivity
import com.controlswitch.R
import com.controlswitch.domain.model.lockedPlayer1
import com.controlswitch.ui.theme.Palette
import kotlinx.coroutines.delay

internal fun Context.canPinShortcut(): Boolean =
    getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

// The home app answers with its own confirmation; there is nothing to do with its answer here.
internal fun Context.pinToHome() {
    val shortcut = ShortcutInfo.Builder(this, "open")
        .setShortLabel(getString(R.string.app_name))
        .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
        .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        .build()
    getSystemService(ShortcutManager::class.java)?.requestPinShortcut(shortcut, null)
}

internal fun Context.findMainActivity(): MainActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is MainActivity) return current
        current = current.baseContext
    }
    return null
}

// In order of depth: a higher page slides in from the right, going back slides the other way.
private enum class Page { Main, Settings, Tests, Player1, Mapping }

@Composable
fun MirrorScreen(viewModel: MirrorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Pad charge comes from a root read, so poll it only while the screen is visible. Restarted when
    // the set of pads changes, so a pad that just connected shows its charge at once.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val padNames = state.devices.filter { !it.isInternal }.map { it.name }
    LaunchedEffect(lifecycle, padNames) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshPadBatteries()
                delay(30_000)
            }
        }
    }
    val editorState by viewModel.mappingEditor.state.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var testsOpen by rememberSaveable { mutableStateOf(false) }
    val page = when {
        // A whole screen rather than an overlay: mapping is the only thing happening while it is
        // open, and the controller is answering it instead of driving whatever would show behind.
        editorState.open -> Page.Mapping
        state.pickingPlayer1 -> Page.Player1
        testsOpen -> Page.Tests
        settingsOpen -> Page.Settings
        else -> Page.Main
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalPadLayout provides if (editorState.open) editorState.layout else state.padLayout) {
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize().background(Palette.screen),
        transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (slideInHorizontally(tween(PAGE_MS)) { dir * it / 8 } + fadeIn(tween(PAGE_MS))) togetherWith
                (slideOutHorizontally(tween(PAGE_MS)) { -dir * it / 8 } + fadeOut(tween(PAGE_MS / 2)))
        },
        label = "page",
    ) { shown ->
        // Both pages are composed while one slides over the other. Only the arriving one may take
        // the focus, or the leaving page's re-anchoring pulls it back off the new one.
        val active = shown == page
        when (shown) {
            Page.Mapping -> MappingEditorScreen(
                state = editorState,
                onBind = { viewModel.mappingEditor.bind(it) },
                onClear = { viewModel.mappingEditor.clear(it) },
                onCancelBind = { viewModel.mappingEditor.cancelBind() },
                onClearAll = { viewModel.mappingEditor.clearAll() },
                onClose = { viewModel.mappingEditor.close() },
                controller = viewModel.mappingEditor,
            )
            Page.Player1 -> Player1Screen(
                externals = state.devices.filter { !it.isInternal },
                player1Name = state.player1Name,
                player1Path = state.player1Path,
                pendingName = state.pendingPlayer1Name,
                pendingPath = state.pendingPlayer1Path,
                levels = state.padLevels,
                active = active,
                onPick = viewModel::choosePlayer1,
                onConfirm = viewModel::confirmPlayer1,
                onClose = viewModel::closePlayer1Picker,
            )
            Page.Tests -> ControllerTestScreen(state, active, onBack = { testsOpen = false })
            Page.Settings -> AdditionalSettingsScreen(
                state, viewModel, active,
                onOpenTests = { testsOpen = true }, onBack = { settingsOpen = false },
            )
            Page.Main -> MainPage(state, viewModel, active, onOpenSettings = { settingsOpen = true })
        }
    }
    // Only the main page: it is where B closes the app. Elsewhere B just goes back, and the tests
    // and the picker stand the mirror down on purpose and read the pad raw.
    if (page == Page.Main) StartingGuard(state.starting)

    }
    }


    if (state.unsupported) {
        UnsupportedDeviceDialog(serviceUnresponsive = state.serviceUnresponsive)
    }
}

private const val PAGE_MS = 220

@Composable
private fun MainPage(state: MirrorUiState, viewModel: MirrorViewModel, active: Boolean, onOpenSettings: () -> Unit) {
    var pickerOpen by remember { mutableStateOf(false) }
    var screenHasFocus by remember { mutableStateOf(false) }
    // Initial gamepad focus prefers the first row and falls back to the always-enabled mirroring
    // row, for when the first is disabled (busy).
    val topFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    val context = LocalContext.current

    // Anchor gamepad focus on the first control, leaving touch mode first. On a touchscreen handheld
    // the window starts in Touch input mode, in which requestFocus() is a silent no-op and the D-pad
    // and stick have no anchor to navigate from until a face button hands focus out.
    val inputModeManager = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    // Reports whether a target actually took the focus. The caller needs to know: a request made
    // before the node is placed fails silently, and there is no other way to tell.
    val anchorFocus: () -> Boolean = remember(inputModeManager, topFocus, primaryFocus) {
        {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            runCatching { topFocus.requestFocus() }.isSuccess ||
                runCatching { primaryFocus.requestFocus() }.isSuccess
        }
    }

    // Re-anchor the instant the window gains focus. The launch-time effect below can lose the race
    // when the window isn't focused yet (e.g. while controllers are scanned), so without this the
    // D-pad/stick have no focus to move from until a button press hands it out.
    DisposableEffect(context, anchorFocus, active) {
        val activity = context.findMainActivity()
        // The Activity hook wants a plain callback; whether the anchor took is only the retry
        // loop's business.
        // Only when nothing here holds focus: returning from a dialog or the shade used to throw the
        // user back to the top row from wherever they were.
        if (active) activity?.onWindowFocused = { if (!screenHasFocus) anchorFocus() }
        onDispose { if (active) activity?.onWindowFocused = null }
    }

    // Give the gamepad a focus anchor so the D-pad works immediately, retrying across a few frames
    // because the focus node is not necessarily placed on the first pass.
    //
    // This page is composed afresh every time it comes back (from the editor, the picker, settings),
    // and it must take the focus back — the other page's rows are gone and nothing else holds it.
    //
    // The loop stops on OBSERVED focus, not on what requestFocus() reported. That distinction is the
    // whole bug: on the return trip the anchor node exists immediately, so the request "succeeds" on
    // the first frame while the focus goes nowhere, the loop congratulates itself and exits, and the
    // pad is left with nothing to navigate from.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // Let go of the focus before asking for it. Leaving the other page destroys the node that held
        // it, and Compose is then left pointing at something that no longer exists — in that state a
        // fresh requestFocus() is refused outright, which is why the retry loop alone was not enough.
        focusManager.clearFocus(force = true)
        repeat(24) {
            if (screenHasFocus) return@LaunchedEffect
            anchorFocus()
            // Belt and braces: if neither named anchor will take it, ask the focus system for the
            // first thing it can find. Landing somewhere unexpected still beats landing nowhere, which
            // costs the user the whole app until they restart it.
            if (!screenHasFocus) focusManager.moveFocus(FocusDirection.Down)
            delay(50)
        }
    }

    // When a state change disables whatever had focus — toggling the mirror briefly makes the
    // rows busy, and starting/stopping it locks the handheld tile — Compose clears focus and the
    // D-pad has nothing to navigate from. Re-anchor so gamepad navigation survives every transition.
    LaunchedEffect(state.enabled, state.autoMirrorEnabled, state.busy, active) {
        if (active && !screenHasFocus) anchorFocus()
    }

    val externals = state.devices.filter { !it.isInternal }
    val lockedPad = externals.lockedPlayer1(state.player1Name, state.player1Path)
    // Batteries are binder calls; read again only when the snapshot moves, not every recomposition.
    val systemBattery = remember(state.devices, state.enabled) { handheldBattery(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Whether anything at all on this page holds focus. `hasFocus` covers the whole subtree,
            // so one modifier on the root answers a question no FocusRequester can: requestFocus()
            // reports success as soon as its node exists, which is not the same as the node having
            // taken focus.
            .onFocusChanged { screenHasFocus = it.hasFocus }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Header(loading = state.loading)
        Rule(Modifier.padding(horizontal = 28.dp))

        Row(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 28.dp)) {
            // Left: the handheld, then the pads it can see, player 1's lamp lit.
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    SectionLabel("System")
                    HandheldTile(
                        battery = systemBattery,
                        hint = when {
                            state.autoMirrorEnabled || state.busy -> null
                            state.hasKnownInternalProfile -> "Auto-detected"
                            else -> "Press to change built-in controller"
                        },
                        onClick = if (state.busy || state.autoMirrorEnabled) null else ({ pickerOpen = true }),
                    )
                }
                Rule(Modifier.padding(horizontal = 24.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    SectionLabel("Controllers")
                    val missing = state.player1Name?.takeIf { lockedPad == null }
                    when {
                        missing != null -> Note("$missing isn't connected. Change Input Order to pick another.")
                        externals.isEmpty() -> Note("Connect a controller to mirror it.")
                    }
                    if (externals.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth()) {
                            externals.take(4).forEach { pad ->
                                PadTile(
                                    device = pad,
                                    player1 = pad.path == state.externalDevice?.path,
                                    levels = state.padLevels,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Keep a lone pad from stretching across the whole half.
                            repeat(4 - externals.size.coerceAtMost(4)) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            Box(Modifier.fillMaxHeight().width(1.dp).padding(vertical = 16.dp).background(Palette.border))

            // Right: the menu. Four rows, no scrolling.
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                MenuRow(
                    title = "Change Input Order",
                    onClick = if (state.busy) null else viewModel::openPlayer1Picker,
                    modifier = Modifier.focusRequester(topFocus),
                )
                MenuRow(
                    title = "Button Mapping",
                    // Only offered once the controller has an identity to save a mapping against.
                    onClick = if (state.externalDevice?.mappingKey != null) viewModel::openMappingEditor else null,
                )
                MenuRow(title = "Additional Settings", onClick = onOpenSettings)
                MenuRow(
                    title = if (state.autoMirrorEnabled) "End Mirroring" else "Start Mirroring",
                    titleColor = if (state.autoMirrorEnabled) Palette.danger else Palette.textPrimary,
                    // The ViewModel ignores presses while busy, so the row can stay focusable (a
                    // disabled control would drop D-pad focus mid-toggle).
                    onClick = { viewModel.toggleAutoMirrorEnabled(!state.autoMirrorEnabled) },
                    modifier = Modifier.focusRequester(primaryFocus),
                )
            }
        }

        Footer(
            status = when {
                state.loading -> "Scanning controllers..."
                state.restartWaiting -> "Waiting for mirroring to start..."
                else -> state.message
            },
            hints = listOf(HintAction(SlotIcon.B, "Back", null), HintAction(SlotIcon.A, "OK", null)),
        )
    }

    if (pickerOpen) {
        InternalControllerPicker(
            // Drop whatever is currently acting as the external pad: this list answers "which one is
            // built into the handheld?", and the mirror source is never that. Matching on path keeps
            // a placeholder external (resolved from saved identity, absent from the list) from
            // filtering anything out. "Automatic (detect)" stays available to undo a wrong pick.
            devices = state.devices.filter { it.path != state.externalDevice?.path },
            selectedGuid = state.manualInternalGuid,
            onSelect = { guid ->
                pickerOpen = false
                viewModel.selectInternalController(guid)
            },
            onClose = { pickerOpen = false },
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, color = Palette.textMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(4.dp))
}

/**
 * Bottom of every page, on the page colour: a status line left, the button hints right. The hints
 * take the width they need (four of them outgrow half the screen) and the status wraps in the rest.
 */
@Composable
internal fun Footer(status: String, hints: List<HintAction>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(status, color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp).weight(1f))
        HintBar(hints)

    }
}


/**
 * Shown while the mirror is coming up: a notice over the page that takes every pad press and Back.
 * Before the daemon runs, the pad's buttons mean what Android says they mean, and that B is Back
 * closed the app on more than one tester who thought mirroring was already on. Lifts itself after
 * [STARTING_GUARD_MS] so a start that fails (the supervisor retries in a minute) never locks the
 * screen; the footer still says what is happening.
 */
@Composable
private fun StartingGuard(starting: Boolean) {
    var gaveUp by remember { mutableStateOf(false) }
    LaunchedEffect(starting) {
        gaveUp = false
        if (starting) {
            delay(STARTING_GUARD_MS)
            gaveUp = true
        }
    }
    if (!starting || gaveUp) return
    BackHandler { }
    val activity = LocalContext.current.findMainActivity()
    DisposableEffect(activity) {
        val swallowKey: (KeyEvent) -> Boolean = { event ->
            KeyEvent.isGamepadButton(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER
        }
        val swallowMotion: (MotionEvent) -> Boolean = { true }
        val previousKey = activity?.onPadCapture
        val previousMotion = activity?.onPadMotion
        activity?.onPadCapture = swallowKey
        activity?.onPadMotion = swallowMotion
        onDispose {
            // Only if still ours: another page may have installed its own hooks since.
            if (activity?.onPadCapture === swallowKey) activity.onPadCapture = previousKey
            if (activity?.onPadMotion === swallowMotion) activity.onPadMotion = previousMotion
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxSize().background(Palette.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(shape).background(Palette.surface).border(1.dp, Palette.border, shape)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Starting mirroring…", color = Palette.textPrimary, fontSize = 17.sp)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.width(260.dp), color = Palette.cyan, trackColor = Palette.border)
            Spacer(Modifier.height(14.dp))
            Text(
                "Please wait until mirroring is active before using your controller.",
                color = Palette.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

private const val STARTING_GUARD_MS = 15_000L
