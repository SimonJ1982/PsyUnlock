package com.example.lockscreen

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// ---- Haptic feedback ----
fun performHapticFeedback(context: Context) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
}

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("lockscreen_settings", MODE_PRIVATE) }
    private val fullscreenHandler = Handler(Looper.getMainLooper())
    private var originalScreenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private fun updateSimulatedScreenOff(screenOff: Boolean, keepAwake: Boolean) {
        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val attributes = window.attributes
        attributes.screenBrightness = if (screenOff) 0.01f else originalScreenBrightness
        window.attributes = attributes
    }

    data class SettingsSnapshot(
        val backgroundUri: String?,
        val lockScreenTextMode: String,
        val pinLength: Int,
        val wrongAttemptsLimit: Int,
        val unlockMethod: String,
        val hapticFeedbackEnabled: Boolean,
        val unlockReadyIndicator: String,
        val onLaunchScreen: String
    )

    private fun loadAllSettings(): SettingsSnapshot {
        return SettingsSnapshot(
            backgroundUri = prefs.getString("background_uri", null),
            lockScreenTextMode = prefs.getString("lock_screen_text_mode", "date_time")!!,
            pinLength = prefs.getInt("pin_length", 4).coerceIn(4, 6),
            wrongAttemptsLimit = prefs.getInt("wrong_attempts_limit", 3).coerceIn(1, 20),
            unlockMethod = prefs.getString("unlock_method", "volume_up")!!,
            hapticFeedbackEnabled = prefs.getBoolean("haptic_feedback_enabled", true),
            unlockReadyIndicator = prefs.getString("unlock_ready_indicator", "remove_comma")!!,
            onLaunchScreen = prefs.getString("on_launch_screen", "home")!!
        )
    }

    private fun saveSettings(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        val updated = transform(loadAllSettings())
        prefs.edit {
            putString("background_uri", updated.backgroundUri)
            putString("lock_screen_text_mode", updated.lockScreenTextMode)
            putInt("pin_length", updated.pinLength)
            putInt("wrong_attempts_limit", updated.wrongAttemptsLimit)
            putString("unlock_method", updated.unlockMethod)
            putBoolean("haptic_feedback_enabled", updated.hapticFeedbackEnabled)
            putString("unlock_ready_indicator", updated.unlockReadyIndicator)
            putString("on_launch_screen", updated.onLaunchScreen)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        originalScreenBrightness = window.attributes.screenBrightness
        makeFullscreen()
        FakeLockScreenState.fullReset()

        val initialScreen = when (loadAllSettings().onLaunchScreen) {
            "lockscreen" -> "lockscreen"
            else -> "home"
        }

        setContent {
            val configuration = LocalConfiguration.current
            val originalDensity = LocalDensity.current
            val layoutScale = minOf(
                configuration.screenWidthDp / 393f,
                configuration.screenHeightDp / 852f
            ).coerceAtMost(1.20f)

            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = originalDensity.density * layoutScale,
                    fontScale = originalDensity.fontScale
                )
            ) {
                LockScreenApp(
                    initialScreen = initialScreen,
                    loadSettings = ::loadAllSettings,
                    onSaveBackground = { uri -> saveSettings { it.copy(backgroundUri = uri) } },
                    onSaveLockScreenTextMode = { mode ->
                        saveSettings { current ->
                            val newIndicator = if (mode != "date_time" && current.unlockReadyIndicator == "remove_comma") "enter_key_dot" else current.unlockReadyIndicator
                            current.copy(lockScreenTextMode = mode, unlockReadyIndicator = newIndicator)
                        }
                    },
                    onSavePinLength = { len -> saveSettings { it.copy(pinLength = len.coerceIn(4, 6)) } },
                    onSaveWrongAttemptsLimit = { lim -> saveSettings { it.copy(wrongAttemptsLimit = lim.coerceIn(1, 20)) } },
                    onSaveUnlockMethod = { method -> saveSettings { it.copy(unlockMethod = method) } },
                    onSaveHapticEnabled = { en -> saveSettings { it.copy(hapticFeedbackEnabled = en) } },
                    onSaveUnlockReadyIndicator = { indicator ->
                        saveSettings { current ->
                            val valid = indicator != "remove_comma" || current.lockScreenTextMode == "date_time"
                            if (valid) current.copy(unlockReadyIndicator = indicator) else current
                        }
                    },
                    onSaveOnLaunchScreen = { screen -> saveSettings { it.copy(onLaunchScreen = screen) } },
                    onUnlock = {
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                    onDisplayStateChanged = ::updateSimulatedScreenOff
                )
            }
        }
        forceFullscreen()
    }

    private fun makeFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun forceFullscreen() {
        makeFullscreen()
        fullscreenHandler.removeCallbacksAndMessages(null)
        fullscreenHandler.post { makeFullscreen() }
        fullscreenHandler.postDelayed({ makeFullscreen() }, 100)
        fullscreenHandler.postDelayed({ makeFullscreen() }, 300)
    }

    override fun onResume() {
        super.onResume()
        forceFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) forceFullscreen()
    }

    override fun onDestroy() {
        fullscreenHandler.removeCallbacksAndMessages(null)
        updateSimulatedScreenOff(screenOff = false, keepAwake = false)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (loadAllSettings().unlockMethod == "volume_up") FakeLockScreenState.volumeUpPressed = true
                forceFullscreen()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                FakeLockScreenState.triggerPinReset()
                forceFullscreen()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            forceFullscreen()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

object FakeLockScreenState {
    var volumeUpPressed by mutableStateOf(false)
    var pinResetTrigger by mutableIntStateOf(0)

    fun fullReset() {
        volumeUpPressed = false
        pinResetTrigger = 0
    }

    fun triggerPinReset() {
        volumeUpPressed = false
        pinResetTrigger += 1
    }
}

@Composable
fun LockScreenApp(
    initialScreen: String,
    loadSettings: () -> MainActivity.SettingsSnapshot,
    onSaveBackground: (String?) -> Unit,
    onSaveLockScreenTextMode: (String) -> Unit,
    onSavePinLength: (Int) -> Unit,
    onSaveWrongAttemptsLimit: (Int) -> Unit,
    onSaveUnlockMethod: (String) -> Unit,
    onSaveHapticEnabled: (Boolean) -> Unit,
    onSaveUnlockReadyIndicator: (String) -> Unit,
    onSaveOnLaunchScreen: (String) -> Unit,
    onUnlock: () -> Unit,
    onDisplayStateChanged: (Boolean, Boolean) -> Unit
) {
    var currentScreen by remember { mutableStateOf(initialScreen) }
    var settings by remember(FakeLockScreenState.pinResetTrigger) { mutableStateOf(loadSettings()) }
    var psyPinLength by remember { mutableIntStateOf(4) }
    var simulatedScreenOff by remember { mutableStateOf(false) }
    var wakeSignal by remember { mutableIntStateOf(0) }

    LaunchedEffect(FakeLockScreenState.pinResetTrigger) {
        settings = loadSettings()
    }

    LaunchedEffect(simulatedScreenOff, currentScreen) {
        val keepAwake = simulatedScreenOff || currentScreen == "lockscreen" ||
                currentScreen == "psy_lockscreen" || currentScreen == "psy_keypad_4" ||
                currentScreen == "psy_keypad_6"
        onDisplayStateChanged(simulatedScreenOff, keepAwake)
    }

    DisposableEffect(Unit) {
        onDispose { onDisplayStateChanged(false, false) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            "home" -> HomeScreen(
                onInstructions = { currentScreen = "instructions" },
                onSettings = { currentScreen = "settings" },
                onPerform = { FakeLockScreenState.fullReset(); wakeSignal = 0; simulatedScreenOff = true; currentScreen = "lockscreen" },
                onPsyUnlock4PIN = { wakeSignal = 0; simulatedScreenOff = false; currentScreen = "psy_keypad_4" },
                onPsyUnlock6PIN = { wakeSignal = 0; simulatedScreenOff = false; currentScreen = "psy_keypad_6" },
                onPsyUnlockSettings = { currentScreen = "psy_settings" }
            )
            "instructions" -> InstructionsScreen(onBack = { currentScreen = "home" })
            "settings" -> SettingsScreen(
                currentSettings = settings,
                onUpdateSettings = { settings = it },
                onSaveBackground = onSaveBackground,
                onSaveLockScreenTextMode = onSaveLockScreenTextMode,
                onSavePinLength = onSavePinLength,
                onSaveWrongAttemptsLimit = onSaveWrongAttemptsLimit,
                onSaveUnlockMethod = onSaveUnlockMethod,
                onSaveHapticEnabled = onSaveHapticEnabled,
                onSaveUnlockReadyIndicator = onSaveUnlockReadyIndicator,
                onSaveOnLaunchScreen = onSaveOnLaunchScreen,
                onBack = { currentScreen = "home" },
                onPerform = { FakeLockScreenState.fullReset(); wakeSignal = 0; simulatedScreenOff = true; currentScreen = "lockscreen" }
            )
            "lockscreen" -> LockScreenEntry(
                settings = settings,
                resetSignal = FakeLockScreenState.pinResetTrigger,
                onUnlock = onUnlock,
                onOpenSettings = { currentScreen = "settings" },
                wakeSignal = wakeSignal
            )
            "psy_keypad_4", "psy_keypad_6" -> PsyKeypadScreen(
                pinLength = if (currentScreen == "psy_keypad_4") 4 else 6,
                onEnter = { length ->
                    psyPinLength = length
                    FakeLockScreenState.fullReset()
                    wakeSignal = 0
                    simulatedScreenOff = true
                    currentScreen = "psy_lockscreen"
                },
                onBackToHome = { currentScreen = "home" },
                onOpenPsySettings = { currentScreen = "psy_settings" }
            )
            "psy_lockscreen" -> LockScreenEntry(
                settings = settings.copy(pinLength = psyPinLength),
                resetSignal = FakeLockScreenState.pinResetTrigger,
                onUnlock = onUnlock,
                onOpenSettings = { currentScreen = "psy_settings" },
                psyMode = true,
                wakeSignal = wakeSignal
            )
            "psy_settings" -> PsyUnlockSettingsScreen(
                onBack = { currentScreen = "home" },
                onPsyUnlock4PIN = { wakeSignal = 0; simulatedScreenOff = false; currentScreen = "psy_keypad_4" },
                onPsyUnlock6PIN = { wakeSignal = 0; simulatedScreenOff = false; currentScreen = "psy_keypad_6" }
            )
        }
        if (simulatedScreenOff) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            simulatedScreenOff = false
                            wakeSignal += 1
                        }
                    }
            )
        }
    }
}

private val DecorativeFont = FontFamily.Serif

@Composable
fun HomeScreen(
    onInstructions: () -> Unit,
    onSettings: () -> Unit,
    onPerform: () -> Unit,
    onPsyUnlock4PIN: () -> Unit,
    onPsyUnlock6PIN: () -> Unit,
    onPsyUnlockSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0F1C2E), Color(0xFF050C15), Color(0xFF02060C))
    )

    Box(modifier = Modifier.fillMaxSize().background(bgGradient), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(32.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "SiUnlock",
                fontFamily = DecorativeFont,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 3.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Secure • Simple • Smart",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.5f),
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.weight(1.2f))

            Column(
                modifier = Modifier.fillMaxWidth(0.85f),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                HomeOptionButton(label = "Instructions", subLabel = "") {
                    performHapticFeedback(ctx)
                    onInstructions()
                }
                HomeOptionButton(label = "SiUnlock Settings", subLabel = "") {
                    performHapticFeedback(ctx)
                    onSettings()
                }
                HomeOptionButton(label = "Perform SiUnlock", subLabel = "") {
                    performHapticFeedback(ctx)
                    onPerform()
                }
                HomeOptionButton(label = "PsyUnlock 4PIN", subLabel = "") {
                    performHapticFeedback(ctx)
                    onPsyUnlock4PIN()
                }
                HomeOptionButton(label = "PsyUnlock 6PIN", subLabel = "") {
                    performHapticFeedback(ctx)
                    onPsyUnlock6PIN()
                }
                HomeOptionButton(label = "PsyUnlock Settings", subLabel = "") {
                    performHapticFeedback(ctx)
                    onPsyUnlockSettings()
                }
            }
            Spacer(modifier = Modifier.weight(1.5f))
        }
    }
}

@Composable
fun HomeOptionButton(label: String, subLabel: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF1E2D42), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column {
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color.White)
            if (subLabel.isNotEmpty()) {
                Text(subLabel, fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun PsyKeypadScreen(
    pinLength: Int,
    onEnter: (Int) -> Unit,
    onBackToHome: () -> Unit,
    onOpenPsySettings: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = remember {
        ctx.getSharedPreferences("lockscreen_settings", Context.MODE_PRIVATE)
    }

    val savedPinKey = if (pinLength == 4) {
        "psyunlock_4pin"
    } else {
        "psyunlock_6pin"
    }

    var enteredPin by remember { mutableStateOf("") }
    var submittedPin by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(submittedPin) {
        if (submittedPin != null) {
            delay(3.seconds)
            onEnter(pinLength)
        }
    }

    fun appendDigit(digit: String) {
        if (enteredPin.length < pinLength) {
            enteredPin += digit
        }
    }

    fun deleteLast() {
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
        }
    }

    fun savePinAndContinue() {
        if (enteredPin.length == pinLength) {
            prefs.edit {
                putString(savedPinKey, enteredPin)
            }
            submittedPin = enteredPin
        }
    }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF15263D), Color(0xFF07111F), Color(0xFF02060C))
    )
    val keySize = 75.dp
    val smallKeySize = keySize / 2
    val gap = 22.dp
    val pinDotDiameter = 22.dp
    val keyTextSize = 26.67.sp
    val smallTextSize = keyTextSize * 0.75f
    val keyColor = Color(0xFF707070)
    val enterEnabled = enteredPin.length == pinLength

    Box(modifier = Modifier.fillMaxSize().background(bgGradient)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp, 48.dp, 16.dp, 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Enter $pinLength digits",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                repeat(pinLength) { index ->
                    val filled = index < enteredPin.length
                    Box(modifier = Modifier.size(pinDotDiameter), contentAlignment = Alignment.Center) {
                        if (filled) Text(enteredPin[index].toString(), color = Color.White, fontSize = 22.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    NumberButton("1", keySize, keyColor, keyTextSize) {
                        performHapticFeedback(ctx)
                        appendDigit("1")
                    }
                    NumberButton("2", keySize, keyColor, keyTextSize) {
                        performHapticFeedback(ctx)
                        appendDigit("2")
                    }
                    NumberButton("3", keySize, keyColor, keyTextSize) {
                        performHapticFeedback(ctx)
                        appendDigit("3")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    NumberButton("4", keySize, keyColor, keyTextSize) {
                        performHapticFeedback(ctx)
                        appendDigit("4")
                    }
                    NumberButton("5", keySize, keyColor, keyTextSize) {
                        performHapticFeedback(ctx)
                        appendDigit("5")
                    }
                    NumberButton("6", keySize, keyColor, keyTextSize) {
                        performHapticFeedback(ctx)
                        appendDigit("6")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    NumberButton("7", keySize, keyColor, keyTextSize) {
                        performHapticFeedback(ctx)
                        appendDigit("7")
                    }
                    NumberButton("8", keySize, keyColor, keyTextSize) {
                        performHapticFeedback(ctx)
                        appendDigit("8")
                    }
                    NumberButton("9", keySize, keyColor, keyTextSize) {
                        performHapticFeedback(ctx)
                        appendDigit("9")
                    }
                }
                Row(
                    modifier = Modifier.width(keySize * 3 + gap * 2),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(keySize), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(smallKeySize)
                                .clickable {
                                    performHapticFeedback(ctx)
                                    deleteLast()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⌫", color = Color.White, fontSize = smallTextSize, fontWeight = FontWeight.Light)
                        }
                    }
                    NumberButton("0", keySize, keyColor, keyTextSize) {
                        performHapticFeedback(ctx)
                        appendDigit("0")
                    }
                    Box(modifier = Modifier.size(keySize), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(smallKeySize)
                                .background(
                                    if (enterEnabled) keyColor else keyColor.copy(alpha = 0.35f),
                                    CircleShape
                                )
                                .clickable(enabled = enterEnabled) {
                                    performHapticFeedback(ctx)
                                    savePinAndContinue()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("→|", color = Color.White, fontSize = smallTextSize, fontWeight = FontWeight.Light)
                        }
                    }
                }
            }
        }
        if (submittedPin != null) {
            Box(
                modifier = Modifier.fillMaxHeight(0.75f).align(Alignment.TopCenter),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    "$submittedPin submitted",
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp))
                    .clickable { performHapticFeedback(ctx); onBackToHome() },
                contentAlignment = Alignment.Center
            ) {
                Text("Back to Home Screen", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp))
                    .clickable { performHapticFeedback(ctx); onOpenPsySettings() },
                contentAlignment = Alignment.Center
            ) {
                Text("PsyUnlock Setting", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun PsyUnlockSettingsScreen(
    onBack: () -> Unit,
    onPsyUnlock4PIN: () -> Unit,
    onPsyUnlock6PIN: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = remember(ctx) { ctx.getSharedPreferences("lockscreen_settings", Context.MODE_PRIVATE) }
    var attemptLimit by remember { mutableIntStateOf(prefs.getInt("psyunlock_wrong_attempts_limit", 3).coerceIn(1, 20)) }
    var waitTimeSeconds by remember { mutableIntStateOf(prefs.getInt("psyunlock_wait_time_seconds", 0).coerceAtLeast(0)) }
    var waitTimeStepSeconds by remember { mutableIntStateOf(1) }
    var pauseTimeMilliseconds by remember {
        mutableIntStateOf(
            prefs.getInt(
                "psyunlock_pause_time_milliseconds",
                prefs.getInt("psyunlock_pause_time_seconds", 0).coerceAtLeast(0) * 1000
            ).coerceAtLeast(0)
        )
    }
    var pauseTimeStepMilliseconds by remember { mutableIntStateOf(1000) }
    var glitchEnabled by remember { mutableStateOf(prefs.getBoolean("psyunlock_glitch_enabled", false)) }
    var glitchLengthMilliseconds by remember {
        mutableIntStateOf(prefs.getInt("psyunlock_glitch_length_milliseconds", 5000).coerceAtLeast(0))
    }
    var glitchLengthStepMilliseconds by remember { mutableIntStateOf(1000) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF02060C))) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "PsyUnlock Settings",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(30.dp))

            Text("Wrong attempts", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Number of wrong tries before next correct PIN unlocks automatically",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(58.dp)
                        .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                        .clickable {
                            performHapticFeedback(ctx)
                            if (attemptLimit > 1) {
                                attemptLimit -= 1
                                prefs.edit { putInt("psyunlock_wrong_attempts_limit", attemptLimit) }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("−", color = Color.White, fontSize = 28.sp)
                }
                Box(
                    modifier = Modifier.size(96.dp, 58.dp)
                        .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$attemptLimit",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Box(
                    modifier = Modifier.size(58.dp)
                        .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                        .clickable {
                            performHapticFeedback(ctx)
                            if (attemptLimit < 20) {
                                attemptLimit += 1
                                prefs.edit { putInt("psyunlock_wrong_attempts_limit", attemptLimit) }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = Color.White, fontSize = 28.sp)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("Wait time", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "The length of time from the last wrong PIN entry to the start of unlock animation.",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(58.dp)
                        .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                        .clickable {
                            performHapticFeedback(ctx)
                            if (waitTimeSeconds > 0) {
                                waitTimeSeconds = (waitTimeSeconds - waitTimeStepSeconds).coerceAtLeast(0)
                                prefs.edit { putInt("psyunlock_wait_time_seconds", waitTimeSeconds) }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("−", color = Color.White, fontSize = 28.sp)
                }
                Box(
                    modifier = Modifier.size(96.dp, 58.dp)
                        .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$waitTimeSeconds s",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Box(
                    modifier = Modifier.size(58.dp)
                        .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                        .clickable {
                            performHapticFeedback(ctx)
                            waitTimeSeconds += waitTimeStepSeconds
                            prefs.edit { putInt("psyunlock_wait_time_seconds", waitTimeSeconds) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = Color.White, fontSize = 28.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(5, 10, 30).forEach { sec ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .background(
                                if (waitTimeStepSeconds == sec) Color(0xFF64B5F6) else Color(0xFF1E2D42),
                                RoundedCornerShape(18.dp)
                            )
                            .clickable {
                                performHapticFeedback(ctx)
                                waitTimeStepSeconds = if (waitTimeStepSeconds == sec) 1 else sec
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$sec s",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("Pause time", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "The pause between button presses in the unlock animation",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(58.dp)
                        .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                        .clickable {
                            performHapticFeedback(ctx)
                            if (pauseTimeMilliseconds > 0) {
                                pauseTimeMilliseconds = (pauseTimeMilliseconds - pauseTimeStepMilliseconds).coerceAtLeast(0)
                                prefs.edit { putInt("psyunlock_pause_time_milliseconds", pauseTimeMilliseconds) }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("−", color = Color.White, fontSize = 28.sp)
                }
                Box(
                    modifier = Modifier.size(96.dp, 58.dp)
                        .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (pauseTimeMilliseconds % 1000 == 0) {
                            "${pauseTimeMilliseconds / 1000} s"
                        } else {
                            "${pauseTimeMilliseconds / 1000}.5 s"
                        },
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Box(
                    modifier = Modifier.size(58.dp)
                        .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                        .clickable {
                            performHapticFeedback(ctx)
                            pauseTimeMilliseconds += pauseTimeStepMilliseconds
                            prefs.edit { putInt("psyunlock_pause_time_milliseconds", pauseTimeMilliseconds) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = Color.White, fontSize = 28.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(500, 5000, 10000, 30000).forEach { milliseconds ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .background(
                                if (pauseTimeStepMilliseconds == milliseconds) Color(0xFF64B5F6) else Color(0xFF1E2D42),
                                RoundedCornerShape(18.dp)
                            )
                            .clickable {
                                performHapticFeedback(ctx)
                                pauseTimeStepMilliseconds = if (pauseTimeStepMilliseconds == milliseconds) 1000 else milliseconds
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (milliseconds == 500) "0.5 s" else "${milliseconds / 1000} s",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            LegacySettingsOption(label = "Glitch", selected = glitchEnabled) {
                performHapticFeedback(ctx)
                glitchEnabled = !glitchEnabled
                prefs.edit { putBoolean("psyunlock_glitch_enabled", glitchEnabled) }
            }
            if (glitchEnabled) {
                Spacer(modifier = Modifier.height(28.dp))
                Text("Glitch length", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(58.dp)
                            .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                            .clickable {
                                performHapticFeedback(ctx)
                                if (glitchLengthMilliseconds > 0) {
                                    glitchLengthMilliseconds = (glitchLengthMilliseconds - glitchLengthStepMilliseconds).coerceAtLeast(0)
                                    prefs.edit { putInt("psyunlock_glitch_length_milliseconds", glitchLengthMilliseconds) }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("−", color = Color.White, fontSize = 28.sp)
                    }
                    Box(
                        modifier = Modifier.size(96.dp, 58.dp)
                            .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (glitchLengthMilliseconds % 1000 == 0) {
                                "${glitchLengthMilliseconds / 1000} s"
                            } else {
                                "${glitchLengthMilliseconds / 1000}.5 s"
                            },
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Box(
                        modifier = Modifier.size(58.dp)
                            .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                            .clickable {
                                performHapticFeedback(ctx)
                                glitchLengthMilliseconds += glitchLengthStepMilliseconds
                                prefs.edit { putInt("psyunlock_glitch_length_milliseconds", glitchLengthMilliseconds) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", color = Color.White, fontSize = 28.sp)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(500, 5000, 10000, 30000).forEach { milliseconds ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp)
                                .background(
                                    if (glitchLengthStepMilliseconds == milliseconds) Color(0xFF64B5F6) else Color(0xFF1E2D42),
                                    RoundedCornerShape(18.dp)
                                )
                                .clickable {
                                    performHapticFeedback(ctx)
                                    glitchLengthStepMilliseconds = if (glitchLengthStepMilliseconds == milliseconds) 1000 else milliseconds
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (milliseconds == 500) "0.5 s" else "${milliseconds / 1000} s",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp))
                    .clickable { performHapticFeedback(ctx); onPsyUnlock4PIN() },
                contentAlignment = Alignment.Center
            ) {
                Text("Perform PsyUnlock 4PIN", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp))
                    .clickable { performHapticFeedback(ctx); onPsyUnlock6PIN() },
                contentAlignment = Alignment.Center
            ) {
                Text("Perform PsyUnlock 6PIN", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp))
                    .clickable { performHapticFeedback(ctx); onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("Back to Home Screen", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun NumberButton(
    label: String,
    size: Dp,
    bgColor: Color,
    textSize: TextUnit,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(bgColor, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = textSize, fontWeight = FontWeight.Light)
    }
}

@Composable
fun InstructionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0F1C2E), Color(0xFF050C15), Color(0xFF02060C))
    )

    Box(modifier = Modifier.fillMaxSize().background(bgGradient)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp, 32.dp)
        ) {
            Text(
                "Instructions for SiUnlock",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                "PIN Length",
                fontSize = 18.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Choose between 4-digit and 6-digit PIN protection.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                "Unlock Method",
                fontSize = 18.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Volume Up Button — Press Volume Up to prime the unlock sequence.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Wrong Attempts — After a set number of wrong PIN entries, the next correct PIN unlocks automatically.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                "Lock Screen Display",
                fontSize = 18.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Date & Time — Shows current time and date on the lock screen.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Enter PIN Message — Shows 'Enter PIN' instead of the date/time.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                "Ready Indicator",
                fontSize = 18.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Remove Comma — The comma disappears from the date when the screen is ready to unlock.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Enter Key Dot — A small dot appears below the Enter button when ready.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                "Feedback",
                fontSize = 18.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Haptic Feedback — Your phone vibrates when you tap buttons.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(36.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp))
                    .clickable { performHapticFeedback(ctx); onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("Back to Home Screen", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun LockScreenEntry(
    settings: MainActivity.SettingsSnapshot,
    resetSignal: Int,
    onUnlock: () -> Unit,
    onOpenSettings: () -> Unit,
    psyMode: Boolean = false,
    wakeSignal: Int = 0
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current

    var swipeDragDistance by remember { mutableFloatStateOf(0f) }
    var screenRevealed by remember { mutableStateOf(false) }
    var showQuickGlance by remember { mutableStateOf(false) }
    var faceNotRecognized by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    val wrongAttemptCount = remember { mutableIntStateOf(0) }
    var currentTime by remember { mutableStateOf(Date()) }
    var psyAutoSequenceRunning by remember { mutableStateOf(false) }
    var psyAutoSubmitReady by remember { mutableStateOf(false) }
    var psyPressedKey by remember { mutableStateOf<String?>(null) }
    var psyGlitchFrame by remember { mutableIntStateOf(-1) }

    LaunchedEffect(wakeSignal) {
        if (wakeSignal > 0) showQuickGlance = true
    }

    val revealThreshold = with(density) { 120.dp.toPx() }
    val maxTravelDistance = with(density) { 340.dp.toPx() }
    val progressFraction = (swipeDragDistance / revealThreshold).coerceIn(0f, 1f)

    val pinAreaOffset by animateDpAsState(
        targetValue = if (screenRevealed) 0.dp else with(density) { (maxTravelDistance * (1 - progressFraction)).toDp() },
        animationSpec = tween(durationMillis = 350),
        label = "pinOffset"
    )
    val keypadOffset by animateDpAsState(
        targetValue = if (screenRevealed) 0.dp else with(density) { (maxTravelDistance * (1 - progressFraction)).toDp() },
        animationSpec = tween(durationMillis = 350),
        label = "keypadOffset"
    )

    val keySize = 75.dp
    val smallKeySize = keySize / 2
    val gap = 22.dp
    val pinDotDiameter = 22.dp
    val keyTextSize = 26.67.sp
    val smallTextSize = keyTextSize * 0.75f
    val keyBackgroundAlpha = if (settings.backgroundUri != null && (screenRevealed || progressFraction > 0.3f)) 1f else 0.6f
    val keyColor = Color(0xFF707070).copy(alpha = keyBackgroundAlpha)
    val keypadVerticalOffset = (-57).dp
    fun psyKeyColor(digit: String) = if (psyMode && psyPressedKey == digit) Color(0xFF64B5F6) else keyColor

    val volumeUpArmed = FakeLockScreenState.volumeUpPressed
    val psyWrongAttemptsLimit = remember(psyMode, ctx) {
        if (psyMode) ctx.getSharedPreferences("lockscreen_settings", Context.MODE_PRIVATE)
            .getInt("psyunlock_wrong_attempts_limit", 3).coerceIn(1, 20)
        else 3
    }

    val showCommaRemoval = settings.unlockReadyIndicator == "remove_comma" &&
            when (settings.unlockMethod) {
                "volume_up" -> volumeUpArmed
                "wrong_attempts" -> wrongAttemptCount.intValue >= settings.wrongAttemptsLimit
                else -> false
            }
    val showEnterDot = if (psyMode) {
        wrongAttemptCount.intValue >= psyWrongAttemptsLimit
    } else settings.unlockReadyIndicator == "enter_key_dot" &&
            when (settings.unlockMethod) {
                "volume_up" -> volumeUpArmed
                "wrong_attempts" -> wrongAttemptCount.intValue >= settings.wrongAttemptsLimit
                else -> false
            }

    LaunchedEffect(screenRevealed) {
        faceNotRecognized = false
        if (screenRevealed) {
            delay(3.seconds)
            faceNotRecognized = true
        }
    }

    LaunchedEffect(resetSignal) {
        enteredPin = ""
        statusMessage = ""
        wrongAttemptCount.intValue = 0
        currentTime = Date()
    }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                currentTime = Date()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(ticker)
        onDispose { handler.removeCallbacks(ticker) }
    }

    val timeDisplay = remember(currentTime) {
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(currentTime)
    }
    val dateFull = remember(currentTime) {
        java.text.SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(currentTime)
    }
    val commaIndex = dateFull.indexOf(',')
    val datePart = if (commaIndex >= 0) dateFull.take(commaIndex) else dateFull
    val dateRemainder = if (commaIndex >= 0) dateFull.drop(commaIndex + 2) else ""

    fun clearPin() { enteredPin = "" }

    fun appendDigit(digit: String) {
        if (psyMode && psyAutoSequenceRunning) return
        if (enteredPin.length < settings.pinLength) {
            enteredPin += digit
            statusMessage = ""
        }
    }

    fun deleteLast() {
        if (psyMode && psyAutoSequenceRunning) return
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
            statusMessage = ""
        }
    }

    fun submitPin() {
        if (psyMode) {
            if (psyAutoSubmitReady && enteredPin.length == settings.pinLength) {
                psyAutoSubmitReady = false
                onUnlock()
                return
            }
            if (psyAutoSequenceRunning) return
            if (enteredPin.length == settings.pinLength) {
                wrongAttemptCount.intValue += 1
                clearPin()
                statusMessage = "Wrong PIN. Try again."
            }
            return
        }
        if (enteredPin.length != settings.pinLength) {
            statusMessage = "Wrong PIN. Try again."
            clearPin()
            return
        }

        when (settings.unlockMethod) {
            "volume_up" -> {
                if (volumeUpArmed) {
                    clearPin()
                    statusMessage = ""
                    onUnlock()
                } else {
                    clearPin()
                    statusMessage = "Wrong PIN. Try again."
                }
            }

            "wrong_attempts" -> {
                if (wrongAttemptCount.intValue >= settings.wrongAttemptsLimit) {
                    clearPin()
                    statusMessage = ""
                    onUnlock()
                } else {
                    wrongAttemptCount.intValue += 1
                    clearPin()
                    statusMessage = "Wrong PIN. try again"
                }
            }

            else -> {
                clearPin()
                statusMessage = "Unlocking…"
            }
        }
    }

    if (psyMode) {
        LaunchedEffect(showEnterDot, resetSignal) {
            if (showEnterDot) {
                psyAutoSequenceRunning = true
                try {
                    val psyPrefs = ctx.getSharedPreferences("lockscreen_settings", Context.MODE_PRIVATE)
                    delay(psyPrefs.getInt("psyunlock_wait_time_seconds", 0).coerceAtLeast(0).seconds)
                    if (psyPrefs.getBoolean("psyunlock_glitch_enabled", false)) {
                        val glitchLengthMilliseconds = psyPrefs.getInt("psyunlock_glitch_length_milliseconds", 5000).coerceAtLeast(0)
                        var glitchElapsed = 0
                        while (glitchElapsed < glitchLengthMilliseconds) {
                            psyGlitchFrame += 1
                            val frameDuration = minOf(50, glitchLengthMilliseconds - glitchElapsed)
                            delay(frameDuration.milliseconds)
                            glitchElapsed += frameDuration
                        }
                        psyGlitchFrame = -1
                    }
                    val pauseTimeMilliseconds = psyPrefs.getInt(
                        "psyunlock_pause_time_milliseconds",
                        psyPrefs.getInt("psyunlock_pause_time_seconds", 0).coerceAtLeast(0) * 1000
                    ).coerceAtLeast(0)
                    val savedPinKey = if (settings.pinLength == 4) "psyunlock_4pin" else "psyunlock_6pin"
                    val savedPin = psyPrefs.getString(savedPinKey, null)
                    if (savedPin != null && savedPin.length == settings.pinLength && savedPin.all { it.isDigit() }) {
                        enteredPin = ""
                        statusMessage = ""
                        for (digit in savedPin) {
                            psyPressedKey = digit.toString()
                            delay(180.milliseconds)
                            enteredPin += digit
                            delay(180.milliseconds)
                            psyPressedKey = null
                            delay(pauseTimeMilliseconds.milliseconds)
                        }
                        psyPressedKey = "enter"
                        delay(240.milliseconds)
                        psyAutoSubmitReady = true
                        submitPin()
                    }
                } finally {
                    psyGlitchFrame = -1
                    psyPressedKey = null
                    psyAutoSequenceRunning = false
                }
            }
        }
    }

    val defaultBg = Brush.verticalGradient(
        colors = listOf(Color(0xFF15263D), Color(0xFF07111F), Color(0xFF02060C))
    )

    var backgroundBitmap by remember(settings.backgroundUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(settings.backgroundUri) {
        backgroundBitmap = null
        settings.backgroundUri?.let { uriStr ->
            runCatching {
                val stream = ctx.contentResolver.openInputStream(uriStr.toUri())
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }.getOrNull()?.let { backgroundBitmap = it }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (psyMode && psyGlitchFrame >= 0) Modifier.graphicsLayer {
                    translationX = when (psyGlitchFrame % 5) {
                        0 -> -5f
                        1 -> 4f
                        2 -> -2f
                        3 -> 6f
                        else -> 0f
                    } * density.density
                    translationY = if (psyGlitchFrame % 3 == 0) 2f * density.density else 0f
                } else Modifier
            )
            .then(
                if (!screenRevealed) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { showQuickGlance = true }
                    }
                } else Modifier
            )
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, deltaY ->
                        if (!screenRevealed && deltaY < 0) {
                            swipeDragDistance -= deltaY
                        }
                    },
                    onDragEnd = {
                        if (!screenRevealed && swipeDragDistance >= revealThreshold) {
                            screenRevealed = true
                        } else {
                            swipeDragDistance = 0f
                        }
                    }
                )
            }
    ) {
        backgroundBitmap?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (screenRevealed) 0.35f else 1f - progressFraction * 0.5f),
                contentScale = ContentScale.Crop
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        } ?: Box(modifier = Modifier.fillMaxSize().background(defaultBg))

        if (showQuickGlance && !screenRevealed && progressFraction < 0.1f) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(timeDisplay, color = Color.White, fontSize = 82.sp, fontWeight = FontWeight.Light)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(datePart, color = Color.White.copy(alpha = 0.9f), fontSize = 21.sp)
                    Text(
                        buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    color = if (showCommaRemoval) Color.Transparent else Color.White.copy(alpha = 0.9f)
                                )
                            ) {
                                append(",")
                            }
                            append(" ")
                        },
                        fontSize = 21.sp
                    )
                    Text(dateRemainder, color = Color.White.copy(alpha = 0.9f), fontSize = 21.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("☀", color = Color.White, fontSize = 21.sp)
                    Text("13°", color = Color.White.copy(alpha = 0.9f), fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text("Swipe up to unlock", color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp)
                Spacer(modifier = Modifier.height(40.dp))
            }
        }

        if (progressFraction > 0.05f || screenRevealed) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp, 48.dp, 16.dp, 28.dp)
                    .alpha(if (screenRevealed) 1f else progressFraction.coerceIn(0f, 1f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (settings.lockScreenTextMode == "date_time") {
                        Text(timeDisplay, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(datePart, color = Color.White.copy(alpha = 0.75f), fontSize = 17.sp)
                            Text(
                                buildAnnotatedString {
                                    withStyle(
                                        SpanStyle(
                                            color = if (showCommaRemoval) Color.Transparent else Color.White.copy(alpha = 0.75f)
                                        )
                                    ) {
                                        append(",")
                                    }
                                    append(" ")
                                },
                                fontSize = 17.sp
                            )
                            Text(dateRemainder, color = Color.White.copy(alpha = 0.75f), fontSize = 17.sp)
                        }
                    } else {
                        Text(
                            "Enter PIN",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }

                    AnimatedVisibility(faceNotRecognized) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Face not recognised",
                                color = Color.White,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))
                    Spacer(modifier = Modifier.height(76.dp))

                    Box(modifier = Modifier.offset(y = pinAreaOffset)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            repeat(settings.pinLength) { index ->
                                val filled = index < enteredPin.length
                                Spacer(
                                    modifier = Modifier
                                        .size(pinDotDiameter)
                                        .then(if (filled) Modifier.background(Color.White, CircleShape) else Modifier)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    AnimatedVisibility(statusMessage.isNotEmpty()) {
                        Text(
                            statusMessage,
                            color = Color(0xFFFF8888),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier.offset(y = keypadOffset + keypadVerticalOffset),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            NumberButton("1", keySize, psyKeyColor("1"), keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("1")
                            }
                            NumberButton("2", keySize, psyKeyColor("2"), keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("2")
                            }
                            NumberButton("3", keySize, psyKeyColor("3"), keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("3")
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            NumberButton("4", keySize, psyKeyColor("4"), keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("4")
                            }
                            NumberButton("5", keySize, psyKeyColor("5"), keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("5")
                            }
                            NumberButton("6", keySize, psyKeyColor("6"), keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("6")
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            NumberButton("7", keySize, psyKeyColor("7"), keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("7")
                            }
                            NumberButton("8", keySize, psyKeyColor("8"), keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("8")
                            }
                            NumberButton("9", keySize, psyKeyColor("9"), keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("9")
                            }
                        }

                        Row(
                            modifier = Modifier.width(keySize * 3 + gap * 2),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(keySize), contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier.size(smallKeySize).pointerInput(Unit) {
                                        detectTapGestures(onTap = {
                                            if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                            deleteLast()
                                        })
                                    },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("⌫", color = Color.White, fontSize = smallTextSize, fontWeight = FontWeight.Light)
                                }
                            }

                            NumberButton("0", keySize, psyKeyColor("0"), keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("0")
                            }

                            Box(modifier = Modifier.size(keySize), contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier.size(smallKeySize)
                                        .background(if (psyMode && psyPressedKey == "enter") Color(0xFF64B5F6) else Color.Transparent, CircleShape)
                                        .pointerInput(volumeUpArmed, settings.pinLength, settings.unlockMethod) {
                                            detectTapGestures(onTap = {
                                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                                submitPin()
                                            })
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("→|", color = Color.White, fontSize = smallTextSize, fontWeight = FontWeight.Light)

                                    if (showEnterDot) {
                                        Box(
                                            modifier = Modifier
                                                .size(2.dp)
                                                .offset(y = smallKeySize / 2 - 8.dp)
                                                .background(Color.White, CircleShape)
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(194.dp)
                                .height(64.dp)
                                .background(keyColor, CircleShape)
                                .pointerInput(Unit) {
                                    detectTapGestures(onDoubleTap = {
                                        if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                        onOpenSettings()
                                    })
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Emergency", color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
                                Text("Call", color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        if (psyMode && psyGlitchFrame >= 0) {
            PsyUnlockGlitchOverlay(psyGlitchFrame)
        }
    }
}

@Composable
private fun PsyUnlockGlitchOverlay(frame: Int) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val random = Random(frame * 7919 + 3181)
        val width = size.width
        val height = size.height
        val pixelScale = width / 393f
        val rgb = listOf(Color(0xFFFF1744), Color(0xFF00E5FF), Color(0xFFAA00FF), Color.White)

        if (frame % 6 == 0) {
            drawRect(Color.White.copy(alpha = 0.14f))
        }
        repeat(28) {
            val y = random.nextFloat() * height
            val x = random.nextFloat() * width * 0.55f
            val barWidth = random.nextFloat() * (width - x)
            val barHeight = (1f + random.nextFloat() * 13f) * pixelScale
            val color = rgb[random.nextInt(rgb.size)]
            drawRect(
                color.copy(alpha = 0.25f + random.nextFloat() * 0.6f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight)
            )
            drawRect(
                rgb[random.nextInt(3)].copy(alpha = 0.35f),
                topLeft = Offset((x + 5f * pixelScale).coerceAtMost(width), y + 2f * pixelScale),
                size = Size(barWidth, 2f * pixelScale)
            )
        }
        repeat(9) {
            val y = random.nextFloat() * height
            val bandHeight = (4f + random.nextFloat() * 20f) * pixelScale
            drawRect(
                Color.Black.copy(alpha = 0.4f + random.nextFloat() * 0.48f),
                topLeft = Offset(0f, y),
                size = Size(width, bandHeight)
            )
            drawRect(
                rgb[random.nextInt(3)].copy(alpha = 0.50f),
                topLeft = Offset(0f, y),
                size = Size(width, 2f * pixelScale)
            )
        }
        repeat(130) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            drawRect(
                rgb[random.nextInt(rgb.size)].copy(alpha = 0.2f + random.nextFloat() * 0.7f),
                topLeft = Offset(x, y),
                size = Size((1f + random.nextFloat() * 16f) * pixelScale, (1f + random.nextFloat() * 5f) * pixelScale)
            )
        }
        repeat(34) {
            val y = random.nextFloat() * height
            drawRect(
                Color.White.copy(alpha = random.nextFloat() * 0.30f),
                topLeft = Offset(0f, y),
                size = Size(width, pixelScale.coerceAtLeast(1f))
            )
        }
    }
}

@Composable
fun SettingsScreen(
    currentSettings: MainActivity.SettingsSnapshot,
    onUpdateSettings: (MainActivity.SettingsSnapshot) -> Unit,
    onSaveBackground: (String?) -> Unit,
    onSaveLockScreenTextMode: (String) -> Unit,
    onSavePinLength: (Int) -> Unit,
    onSaveWrongAttemptsLimit: (Int) -> Unit,
    onSaveUnlockMethod: (String) -> Unit,
    onSaveHapticEnabled: (Boolean) -> Unit,
    onSaveUnlockReadyIndicator: (String) -> Unit,
    onSaveOnLaunchScreen: (String) -> Unit,
    onBack: () -> Unit,
    onPerform: () -> Unit
) {
    val ctx = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val uriStr = it.toString()
            onSaveBackground(uriStr)
            onUpdateSettings(currentSettings.copy(backgroundUri = uriStr))
        }
    }

    var previewBitmap by remember(currentSettings.backgroundUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(currentSettings.backgroundUri) {
        previewBitmap = null
        currentSettings.backgroundUri?.let { uriStr ->
            runCatching {
                val stream = ctx.contentResolver.openInputStream(uriStr.toUri())
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }.getOrNull()?.let { previewBitmap = it }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF02060C))) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Options", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(30.dp))

            Text("On launch", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))

            LegacySettingsOption("Home Screen", selected = currentSettings.onLaunchScreen == "home") {
                performHapticFeedback(ctx)
                onSaveOnLaunchScreen("home")
                onUpdateSettings(currentSettings.copy(onLaunchScreen = "home"))
            }

            Spacer(modifier = Modifier.height(10.dp))

            LegacySettingsOption("Direct to lock screen", selected = currentSettings.onLaunchScreen == "no_home") {
                performHapticFeedback(ctx)
                onSaveOnLaunchScreen("no_home")
                onUpdateSettings(currentSettings.copy(onLaunchScreen = "no_home"))
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("PIN length", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))

            LegacySettingsOption("4 digits", selected = currentSettings.pinLength == 4) {
                performHapticFeedback(ctx)
                onSavePinLength(4)
                onUpdateSettings(currentSettings.copy(pinLength = 4))
            }

            Spacer(modifier = Modifier.height(10.dp))

            LegacySettingsOption("6 digits", selected = currentSettings.pinLength == 6) {
                performHapticFeedback(ctx)
                onSavePinLength(6)
                onUpdateSettings(currentSettings.copy(pinLength = 6))
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("Unlock method", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))

            LegacySettingsOption("Volume Up button", selected = currentSettings.unlockMethod == "volume_up") {
                performHapticFeedback(ctx)
                onSaveUnlockMethod("volume_up")
                onUpdateSettings(currentSettings.copy(unlockMethod = "volume_up"))
            }

            Spacer(modifier = Modifier.height(10.dp))

            LegacySettingsOption("Wrong attempts", selected = currentSettings.unlockMethod == "wrong_attempts") {
                performHapticFeedback(ctx)
                onSaveUnlockMethod("wrong_attempts")
                onUpdateSettings(currentSettings.copy(unlockMethod = "wrong_attempts"))
            }

            AnimatedVisibility(visible = currentSettings.unlockMethod == "wrong_attempts") {
                Column {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("Attempts before unlock", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "After this many wrong tries, the next correct PIN unlocks automatically.",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(58.dp)
                                .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                                .clickable {
                                    performHapticFeedback(ctx)
                                    val newVal = (currentSettings.wrongAttemptsLimit - 1).coerceAtLeast(1)
                                    onSaveWrongAttemptsLimit(newVal)
                                    onUpdateSettings(currentSettings.copy(wrongAttemptsLimit = newVal))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("−", color = Color.White, fontSize = 28.sp)
                        }

                        Box(
                            modifier = Modifier.weight(1f).height(58.dp)
                                .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${currentSettings.wrongAttemptsLimit}",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Box(
                            modifier = Modifier.size(58.dp)
                                .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                                .clickable {
                                    performHapticFeedback(ctx)
                                    val newVal = (currentSettings.wrongAttemptsLimit + 1).coerceAtMost(20)
                                    onSaveWrongAttemptsLimit(newVal)
                                    onUpdateSettings(currentSettings.copy(wrongAttemptsLimit = newVal))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", color = Color.White, fontSize = 28.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("Lock screen display", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))

            LegacySettingsOption("Date and time", selected = currentSettings.lockScreenTextMode == "date_time") {
                performHapticFeedback(ctx)
                onSaveLockScreenTextMode("date_time")
                val newShownBy = if (currentSettings.unlockReadyIndicator == "remove_comma") "remove_comma" else "enter_key_dot"
                onSaveUnlockReadyIndicator(newShownBy)
                onUpdateSettings(
                    currentSettings.copy(
                        lockScreenTextMode = "date_time",
                        unlockReadyIndicator = newShownBy
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            LegacySettingsOption("'Enter PIN' message", selected = currentSettings.lockScreenTextMode == "message") {
                performHapticFeedback(ctx)
                onSaveLockScreenTextMode("message")
                onSaveUnlockReadyIndicator("enter_key_dot")
                onUpdateSettings(
                    currentSettings.copy(
                        lockScreenTextMode = "message",
                        unlockReadyIndicator = "enter_key_dot"
                    )
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("Unlock ready indicator", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))

            LegacySettingsOption(
                label = "Remove comma from date",
                selected = currentSettings.unlockReadyIndicator == "remove_comma",
                enabled = currentSettings.lockScreenTextMode == "date_time"
            ) {
                if (currentSettings.lockScreenTextMode == "date_time") {
                    performHapticFeedback(ctx)
                    onSaveUnlockReadyIndicator("remove_comma")
                    onUpdateSettings(currentSettings.copy(unlockReadyIndicator = "remove_comma"))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LegacySettingsOption(
                label = "Dot on Enter key",
                selected = currentSettings.unlockReadyIndicator == "enter_key_dot"
            ) {
                performHapticFeedback(ctx)
                onSaveUnlockReadyIndicator("enter_key_dot")
                onUpdateSettings(currentSettings.copy(unlockReadyIndicator = "enter_key_dot"))
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("Haptic feedback", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))

            LegacySettingsOption(
                label = "Enabled",
                selected = currentSettings.hapticFeedbackEnabled
            ) {
                performHapticFeedback(ctx)
                onSaveHapticEnabled(true)
                onUpdateSettings(currentSettings.copy(hapticFeedbackEnabled = true))
            }

            Spacer(modifier = Modifier.height(10.dp))

            LegacySettingsOption(
                label = "Disabled",
                selected = !currentSettings.hapticFeedbackEnabled
            ) {
                performHapticFeedback(ctx)
                onSaveHapticEnabled(false)
                onUpdateSettings(currentSettings.copy(hapticFeedbackEnabled = false))
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("Background image", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Color(0xFF1E2D42), RoundedCornerShape(20.dp))
                    .clickable {
                        performHapticFeedback(ctx)
                        imagePicker.launch("image/*")
                    },
                contentAlignment = Alignment.Center
            ) {
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } ?: Text(
                    "Tap to select image",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Color(0xFF1E2D42), RoundedCornerShape(18.dp))
                    .clickable {
                        performHapticFeedback(ctx)
                        onSaveBackground(null)
                        onUpdateSettings(currentSettings.copy(backgroundUri = null))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("Reset to default gradient", color = Color.White, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(36.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color(0xFF2A3A4F), RoundedCornerShape(20.dp))
                    .clickable {
                        performHapticFeedback(ctx)
                        onPerform()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PERFORM",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(Color(0xFF2A3A4F), RoundedCornerShape(18.dp))
                    .clickable {
                        performHapticFeedback(ctx)
                        onBack()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("Back to Home Screen", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun LegacySettingsOption(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bgColor = when {
        !enabled -> Color(0xFF1E2D42).copy(alpha = 0.5f)
        selected -> Color(0xFF2A3A4F)
        else -> Color(0xFF1E2D42)
    }

    val textAlpha = if (enabled) 1f else 0.4f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(bgColor, RoundedCornerShape(14.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = textAlpha),
                fontSize = 16.sp
            )

            if (selected) {
                Text(
                    text = "✓",
                    color = Color.White.copy(alpha = textAlpha),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
