
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
import kotlin.time.Duration.Companion.seconds

// ---- Haptic feedback ----
fun performHapticFeedback(context: Context) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
}

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("lockscreen_settings", MODE_PRIVATE) }
    private val fullscreenHandler = Handler(Looper.getMainLooper())

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
        makeFullscreen()
        FakeLockScreenState.fullReset()

        val initialScreen = when (loadAllSettings().onLaunchScreen) {
            "lockscreen" -> "lockscreen"
            else -> "home"
        }

        setContent {
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
                }
            )
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
    onUnlock: () -> Unit
) {
    var currentScreen by remember { mutableStateOf(initialScreen) }
    var settings by remember(FakeLockScreenState.pinResetTrigger) { mutableStateOf(loadSettings()) }

    LaunchedEffect(FakeLockScreenState.pinResetTrigger) {
        settings = loadSettings()
    }

    when (currentScreen) {
        "home" -> HomeScreen(
            onInstructions = { currentScreen = "instructions" },
            onSettings = { currentScreen = "settings" },
            onPerform = { FakeLockScreenState.fullReset(); currentScreen = "lockscreen" },
            onPsyUnlock4PIN = { currentScreen = "psy_keypad_4" },
            onPsyUnlock6PIN = { currentScreen = "psy_keypad_6" },
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
            onPerform = { FakeLockScreenState.fullReset(); currentScreen = "lockscreen" }
        )
        "lockscreen" -> LockScreenEntry(
            settings = settings,
            resetSignal = FakeLockScreenState.pinResetTrigger,
            onUnlock = onUnlock,
            onOpenSettings = { currentScreen = "settings" }
        )
        "psy_keypad_4" -> PsyKeypadScreen(
            pinLength = 4,
            onBack = { currentScreen = "home" }
        )
        "psy_keypad_6" -> PsyKeypadScreen(
            pinLength = 6,
            onBack = { currentScreen = "home" }
        )
        "psy_settings" -> PsyUnlockSettingsScreen(onBack = { currentScreen = "home" })
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
                HomeOptionButton(label = "Instructions", subLabel = "How to use") {
                    performHapticFeedback(ctx)
                    onInstructions()
                }
                HomeOptionButton(label = "Settings", subLabel = "Configure your preferences") {
                    performHapticFeedback(ctx)
                    onSettings()
                }
                HomeOptionButton(label = "Perform", subLabel = "Launch lock screen") {
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
fun PsyKeypadScreen(pinLength: Int, onBack: () -> Unit) {
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

    fun appendDigit(digit: String) {
        if (enteredPin.length < pinLength) {
            enteredPin += digit

            if (enteredPin.length == pinLength) {
                prefs.edit {
                    putString(savedPinKey, enteredPin)
                }
            }
        }
    }

    fun deleteLast() {
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
        }
    }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0F1C2E), Color(0xFF050C15), Color(0xFF02060C))
    )
    val btnSize = 75.dp
    val smallBtnSize = btnSize / 2
    val spacing = 22.dp
    val numColor = Color(0xFF707070)
    val numTextSize = 26.67.sp
    val smallTextSize = numTextSize * 0.75f

    Box(modifier = Modifier.fillMaxSize().background(bgGradient), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                "PsyUnlock ${pinLength}PIN",
                fontFamily = DecorativeFont,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Enter $pinLength digits",
                color = Color.White,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                enteredPin,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.height(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                NumberButton("1", btnSize, numColor, numTextSize) {
                    performHapticFeedback(ctx)
                    appendDigit("1")
                }
                NumberButton("2", btnSize, numColor, numTextSize) {
                    performHapticFeedback(ctx)
                    appendDigit("2")
                }
                NumberButton("3", btnSize, numColor, numTextSize) {
                    performHapticFeedback(ctx)
                    appendDigit("3")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                NumberButton("4", btnSize, numColor, numTextSize) {
                    performHapticFeedback(ctx)
                    appendDigit("4")
                }
                NumberButton("5", btnSize, numColor, numTextSize) {
                    performHapticFeedback(ctx)
                    appendDigit("5")
                }
                NumberButton("6", btnSize, numColor, numTextSize) {
                    performHapticFeedback(ctx)
                    appendDigit("6")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                NumberButton("7", btnSize, numColor, numTextSize) {
                    performHapticFeedback(ctx)
                    appendDigit("7")
                }
                NumberButton("8", btnSize, numColor, numTextSize) {
                    performHapticFeedback(ctx)
                    appendDigit("8")
                }
                NumberButton("9", btnSize, numColor, numTextSize) {
                    performHapticFeedback(ctx)
                    appendDigit("9")
                }
            }
            Row(
                modifier = Modifier.width(btnSize * 3 + spacing * 2),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(btnSize), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier.size(smallBtnSize).clickable {
                            performHapticFeedback(ctx)
                            deleteLast()
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⌫", color = Color.White, fontSize = smallTextSize, fontWeight = FontWeight.Light)
                    }
                }
                NumberButton("0", btnSize, numColor, numTextSize) {
                    performHapticFeedback(ctx)
                    appendDigit("0")
                }
                Box(modifier = Modifier.size(btnSize), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier.size(smallBtnSize).clickable {
                            performHapticFeedback(ctx)
                            if (enteredPin.length == pinLength) {
                                prefs.edit {
                                    putString(savedPinKey, enteredPin)
                                }
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("→|", color = Color.White, fontSize = smallTextSize, fontWeight = FontWeight.Light)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
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
fun PsyUnlockSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var attemptLimit by remember { mutableIntStateOf(3) }
    var waitTimeSeconds by remember { mutableIntStateOf(0) }
    var waitTimeStepSeconds by remember { mutableIntStateOf(1) }

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
                            if (attemptLimit > 1) attemptLimit -= 1
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
                            if (attemptLimit < 20) attemptLimit += 1
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = Color.White, fontSize = 28.sp)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("Wait time", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
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
                            if (waitTimeSeconds > 0) waitTimeSeconds = (waitTimeSeconds - waitTimeStepSeconds).coerceAtLeast(0)
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
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = Color.White, fontSize = 28.sp)
                }
            }

            // ===== ONLY NEW CODE ADDED HERE =====
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
                                if (waitTimeStepSeconds == sec) Color(0xFF2A3A4F) else Color(0xFF1E2D42),
                                RoundedCornerShape(18.dp)
                            )
                            .clickable {
                                performHapticFeedback(ctx)
                                waitTimeStepSeconds = sec
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
            // ===== END OF NEW CODE =====

            Spacer(modifier = Modifier.height(28.dp))
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
    onOpenSettings: () -> Unit
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

    val volumeUpArmed = FakeLockScreenState.volumeUpPressed
    val isUnlockReady = when (settings.unlockMethod) {
        "volume_up" -> volumeUpArmed
        "wrong_attempts" -> wrongAttemptCount.intValue >= settings.wrongAttemptsLimit
        else -> false
    }
    val showCommaRemoval = settings.unlockReadyIndicator == "remove_comma" &&
            settings.lockScreenTextMode == "date_time" && isUnlockReady
    val showEnterDot = isUnlockReady && !showCommaRemoval

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
        if (enteredPin.length < settings.pinLength) {
            enteredPin += digit
            statusMessage = ""
        }
    }
    fun deleteLast() {
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
            statusMessage = ""
        }
    }
    fun submitPin() {
        if (enteredPin.length != settings.pinLength) {
            statusMessage = "Please enter ${settings.pinLength} digits"
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
                    statusMessage = "Wrong PIN — try again"
                }
            }
            else -> {
                clearPin()
                statusMessage = "Unlocking…"
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
                            NumberButton("1", keySize, keyColor, keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("1")
                            }
                            NumberButton("2", keySize, keyColor, keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("2")
                            }
                            NumberButton("3", keySize, keyColor, keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("3")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            NumberButton("4", keySize, keyColor, keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("4")
                            }
                            NumberButton("5", keySize, keyColor, keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("5")
                            }
                            NumberButton("6", keySize, keyColor, keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("6")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            NumberButton("7", keySize, keyColor, keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("7")
                            }
                            NumberButton("8", keySize, keyColor, keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("8")
                            }
                            NumberButton("9", keySize, keyColor, keyTextSize) {
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
                            NumberButton("0", keySize, keyColor, keyTextSize) {
                                if (settings.hapticFeedbackEnabled) performHapticFeedback(ctx)
                                appendDigit("0")
                            }
                            Box(modifier = Modifier.size(keySize), contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier.size(smallKeySize).pointerInput(Unit) {
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