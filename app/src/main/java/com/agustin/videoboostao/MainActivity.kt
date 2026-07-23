package com.agustin.videoboostao

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App()
            }
        }
    }
}

private enum class Screen { Main, SensitiveApps }

@Composable
private fun App() {
    var screen by remember { mutableStateOf(Screen.Main) }
    when (screen) {
        Screen.Main -> MainScreen(onManageApps = { screen = Screen.SensitiveApps })
        Screen.SensitiveApps -> SensitiveAppsScreen(onBack = { screen = Screen.Main })
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // Material You: color dinamico del wallpaper (Android 12+). En este rango
    // de dispositivos (Pixel) siempre disponible; fallback estatico por si acaso.
    val colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        content = content,
    )
}

/** Formas extra-redondeadas al estilo Material 3 Expressive. */
private val ExpressiveShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
)

@Composable
private fun MainScreen(onManageApps: () -> Unit) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var featureEnabled by remember { mutableStateOf(Prefs.featureEnabled(context)) }
    var autoDisableBanks by remember { mutableStateOf(Prefs.autoDisableForBanks(context)) }
    var disabledByBank by remember { mutableStateOf(Prefs.disabledByBank(context)) }
    var fullAuto by remember { mutableStateOf(Prefs.fullAutoShizuku(context)) }
    var shizukuAvailable by remember { mutableStateOf(ShizukuManager.isAvailable()) }
    var shizukuReady by remember { mutableStateOf(ShizukuManager.hasPermission()) }
    var usageAccess by remember { mutableStateOf(Capabilities.hasUsageAccess(context)) }
    var adbReady by remember { mutableStateOf(Capabilities.adbReady(context)) }
    var update by remember { mutableStateOf<UpdateChecker.Update?>(null) }

    LifecycleResumeEffect(Unit) {
        serviceEnabled = isAccessibilityServiceEnabled(context)
        featureEnabled = Prefs.featureEnabled(context)
        autoDisableBanks = Prefs.autoDisableForBanks(context)
        disabledByBank = Prefs.disabledByBank(context)
        fullAuto = Prefs.fullAutoShizuku(context)
        shizukuAvailable = ShizukuManager.isAvailable()
        shizukuReady = ShizukuManager.hasPermission()
        usageAccess = Capabilities.hasUsageAccess(context)
        adbReady = Capabilities.adbReady(context)
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        update = UpdateChecker.check(context)
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            update?.let { UpdateCard(it) }

            HeroCard(serviceEnabled = serviceEnabled, featureEnabled = featureEnabled)

            MasterSwitchCard(
                serviceEnabled = serviceEnabled,
                featureEnabled = featureEnabled,
                onToggle = { enabled ->
                    featureEnabled = enabled
                    Prefs.setFeatureEnabled(context, enabled)
                },
            )

            if (serviceEnabled) {
                FilledTonalButton(
                    onClick = {
                        context.startActivity(
                            Intent("android.media.action.VIDEO_CAMERA")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.btn_try_now))
                }
            }

            if (serviceEnabled) {
                BankCard(
                    autoDisable = autoDisableBanks,
                    onToggleAuto = { enabled ->
                        autoDisableBanks = enabled
                        Prefs.setAutoDisableForBanks(context, enabled)
                    },
                    onTurnOffNow = {
                        VideoBoostService.instance?.disableSelf()
                        serviceEnabled = false
                    },
                    onManageApps = onManageApps,
                    fullAuto = fullAuto,
                    shizukuAvailable = shizukuAvailable,
                    shizukuReady = shizukuReady,
                    usageAccess = usageAccess,
                    adbReady = adbReady,
                    onToggleFullAuto = { want ->
                        fullAuto = want
                        Prefs.setFullAutoShizuku(context, want)
                        if (want && !shizukuReady) {
                            ShizukuManager.requestPermission { granted ->
                                mainHandler.post {
                                    shizukuReady = granted
                                    shizukuAvailable = ShizukuManager.isAvailable()
                                }
                            }
                        }
                    },
                    onGrantShizuku = {
                        ShizukuManager.requestPermission { granted ->
                            mainHandler.post {
                                shizukuReady = granted
                                shizukuAvailable = ShizukuManager.isAvailable()
                            }
                        }
                    },
                    onGrantUsageAccess = {
                        context.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onOpenShizukuSite = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
            }

            // Guía de configuración: siempre visible. Expandida cuando falta
            // configurar; colapsada (consultable) cuando el servicio ya está activo.
            SetupCard(context, serviceEnabled, disabledByBank)

            // Alternativa a Shizuku: emparejar por ADB inalámbrico. Siempre
            // visible porque sirve para dos cosas: activar el servicio la primera
            // vez (si aún no lo está) y el full-auto (re-activación al cerrar apps
            // sensibles). No borra ni sustituye la opción de Shizuku.
            AdbPairingCard(
                serviceEnabled = serviceEnabled,
                onServiceEnabled = { serviceEnabled = isAccessibilityServiceEnabled(context) },
                onAdbReadyChanged = { adbReady = Capabilities.adbReady(context) },
            )

            CostCard()

            if (DonationConfig.DONATE_URL.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(DonationConfig.DONATE_URL))
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.btn_donate))
                }
            }

            Text(
                text = stringResource(R.string.pro_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroCard(serviceEnabled: Boolean, featureEnabled: Boolean) {
    val (title, body, container, onContainer) = when {
        serviceEnabled && featureEnabled -> Quad(
            stringResource(R.string.status_active_title),
            stringResource(R.string.status_active_body),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        serviceEnabled -> Quad(
            stringResource(R.string.status_paused_title),
            stringResource(R.string.status_paused_body),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Quad(
            stringResource(R.string.status_setup_title),
            stringResource(R.string.status_setup_body),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF6CC0FE),
                modifier = Modifier.size(72.dp),
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = onContainer,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = onContainer,
            )
        }
    }
}

@Composable
private fun MasterSwitchCard(
    serviceEnabled: Boolean,
    featureEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.master_switch_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!serviceEnabled) {
                    Text(
                        text = stringResource(R.string.master_switch_hint_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = featureEnabled && serviceEnabled,
                onCheckedChange = onToggle,
                enabled = serviceEnabled,
            )
        }
    }
}

@Composable
private fun SetupCard(context: Context, serviceEnabled: Boolean, disabledByBank: Boolean) {
    if (serviceEnabled) {
        // Servicio listo: guía colapsada y reconsultable.
        var expanded by remember { mutableStateOf(false) }
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setup_card_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.setup_done_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = if (expanded) "▲" else "▼",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) GuidedSteps(context, showActions = false)
            }
        }
    } else {
        // Falta configurar: asistente guiado paso a paso.
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.guided_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (disabledByBank) {
                    Text(
                        text = stringResource(R.string.guided_disabled_by_bank),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    text = stringResource(R.string.guided_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                GuidedSteps(context, showActions = true)
            }
        }
    }
}

@Composable
private fun GuidedSteps(context: Context, showActions: Boolean) {
    StepBlock(
        number = 1,
        title = stringResource(R.string.guided_step1_title),
        body = stringResource(R.string.guided_step1_body),
    ) {
        if (showActions) {
            Button(
                onClick = { openAccessibilitySettingsHighlighted(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.guided_step1_action)) }
            Text(
                text = stringResource(R.string.guided_step1_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    StepBlock(
        number = 2,
        title = stringResource(R.string.guided_step2_title),
        body = stringResource(R.string.guided_step2_body),
    ) {
        if (showActions) {
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.guided_step2_action)) }
        }
    }
    Text(
        text = stringResource(R.string.guided_playprotect),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepBlock(
    number: Int,
    title: String,
    body: String,
    action: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(28.dp),
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            action()
        }
    }
}

@Composable
private fun UpdateCard(update: UpdateChecker.Update) {
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.update_available, update.versionName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.pageUrl)))
            }) {
                Text(stringResource(R.string.btn_download_update))
            }
        }
    }
}

@Composable
private fun BankCard(
    autoDisable: Boolean,
    onToggleAuto: (Boolean) -> Unit,
    onTurnOffNow: () -> Unit,
    onManageApps: () -> Unit,
    fullAuto: Boolean,
    shizukuAvailable: Boolean,
    shizukuReady: Boolean,
    usageAccess: Boolean,
    adbReady: Boolean,
    onToggleFullAuto: (Boolean) -> Unit,
    onGrantShizuku: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    onOpenShizukuSite: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bank_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.bank_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.bank_auto_switch),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Switch(checked = autoDisable, onCheckedChange = onToggleAuto)
            }
            OutlinedButton(onClick = onManageApps, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bank_manage_apps))
            }
            OutlinedButton(onClick = onTurnOffNow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bank_turn_off_now))
            }
            FullAutoSection(
                fullAuto = fullAuto,
                shizukuAvailable = shizukuAvailable,
                shizukuReady = shizukuReady,
                usageAccess = usageAccess,
                adbReady = adbReady,
                onToggleFullAuto = onToggleFullAuto,
                onGrantShizuku = onGrantShizuku,
                onGrantUsageAccess = onGrantUsageAccess,
                onOpenShizukuSite = onOpenShizukuSite,
            )
        }
    }
}

/**
 * Sección "Full-auto y recordatorios" dentro de la tarjeta de apps sensibles.
 * Explica y controla el monitoreo del cierre: notificación silenciosa al abrir
 * + normal al cerrar, y (con Shizuku) re-activación automática.
 */
@Composable
private fun FullAutoSection(
    fullAuto: Boolean,
    shizukuAvailable: Boolean,
    shizukuReady: Boolean,
    usageAccess: Boolean,
    adbReady: Boolean,
    onToggleFullAuto: (Boolean) -> Unit,
    onGrantShizuku: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    onOpenShizukuSite: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.fullauto_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        when {
                            fullAuto && shizukuReady -> R.string.fullauto_status_shizuku
                            adbReady -> R.string.fullauto_status_adb
                            usageAccess -> R.string.fullauto_status_usage
                            else -> R.string.fullauto_status_off
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Text(
                text = stringResource(R.string.fullauto_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Toggle full-auto (Shizuku).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.fullauto_switch),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Switch(checked = fullAuto && shizukuReady, onCheckedChange = onToggleFullAuto)
            }

            // Estado de Shizuku + acción para conceder permiso.
            val shizukuStatus = when {
                shizukuReady -> R.string.fullauto_shizuku_ready
                shizukuAvailable -> R.string.fullauto_shizuku_needs_perm
                else -> R.string.fullauto_shizuku_absent
            }
            Text(
                text = stringResource(shizukuStatus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fullAuto && shizukuAvailable && !shizukuReady) {
                OutlinedButton(onClick = onGrantShizuku, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.fullauto_grant_shizuku))
                }
            }

            // Fallback sin Shizuku: Acceso de uso.
            if (!usageAccess) {
                Text(
                    text = stringResource(R.string.fullauto_usage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onGrantUsageAccess, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.fullauto_grant_usage))
                }
            }

            // Tutorial de Shizuku.
            HorizontalDivider()
            Text(
                text = stringResource(R.string.fullauto_tutorial_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            StepBlock(
                number = 1,
                title = stringResource(R.string.fullauto_step1_title),
                body = stringResource(R.string.fullauto_step1_body),
            ) {
                OutlinedButton(onClick = onOpenShizukuSite, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.fullauto_step1_action))
                }
            }
            StepBlock(
                number = 2,
                title = stringResource(R.string.fullauto_step2_title),
                body = stringResource(R.string.fullauto_step2_body),
            ) {}
            StepBlock(
                number = 3,
                title = stringResource(R.string.fullauto_step3_title),
                body = stringResource(R.string.fullauto_step3_body),
            ) {}
        }
    }
}

/** Fases del emparejamiento ADB inalámbrico. */
private enum class AdbPhase { Idle, Discovering, AwaitingCode, Pairing, Connecting, Enabling }

/**
 * Tarjeta de la alternativa in-app a Shizuku: empareja la app con el "Wireless
 * debugging" del propio dispositivo (ADB inalámbrico) y, con eso, puede activar
 * el servicio de accesibilidad la primera vez y re-activarlo solo (full-auto)
 * al cerrarse una app sensible. No sustituye a Shizuku; es una vía paralela.
 */
@Composable
private fun AdbPairingCard(
    serviceEnabled: Boolean,
    onServiceEnabled: () -> Unit,
    onAdbReadyChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var expanded by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(AdbManager.isPaired(context)) }
    var fullAutoAdb by remember { mutableStateOf(Prefs.fullAutoAdb(context)) }
    var connected by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(AdbPhase.Idle) }
    var code by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    val busy = phase != AdbPhase.Idle

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.adb_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            when {
                                fullAutoAdb && paired -> R.string.adb_status_ready
                                paired -> R.string.adb_status_paired_disconnected
                                else -> R.string.adb_status_off
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Text(
                    text = stringResource(R.string.adb_section_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Paso 1: activar Wireless debugging (Opciones de desarrollador).
                StepBlock(
                    number = 1,
                    title = stringResource(R.string.adb_step1_title),
                    body = stringResource(R.string.adb_step1_body),
                ) {
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                                context.startActivity(
                                    Intent(Settings.ACTION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.adb_open_devoptions)) }
                }

                // Paso 2: emparejar con el código de 6 dígitos.
                StepBlock(
                    number = 2,
                    title = stringResource(R.string.adb_step2_title),
                    body = stringResource(R.string.adb_step2_body),
                ) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text(stringResource(R.string.adb_code_label)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Host/puerto: se autodescubren por mDNS; editables como
                    // respaldo si el descubrimiento falla.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text(stringResource(R.string.adb_host_label)) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(2f),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.adb_port_label)) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            error = null
                            info = null
                            scope.launch {
                                // Autodescubrir el endpoint de pairing si el
                                // usuario no tecleó host:puerto a mano.
                                if (host.isBlank() || port.isBlank()) {
                                    phase = AdbPhase.Discovering
                                    info = context.getString(R.string.adb_discovering)
                                    val ep = AdbManager.discoverPairingEndpoint(context)
                                    if (ep != null) {
                                        host = ep.host
                                        port = ep.port.toString()
                                    }
                                }
                                val p = port.toIntOrNull()
                                if (host.isBlank() || p == null) {
                                    error = context.getString(R.string.adb_error_no_device)
                                    info = null
                                    phase = AdbPhase.Idle
                                    return@launch
                                }
                                phase = AdbPhase.Pairing
                                info = context.getString(R.string.adb_pairing)
                                val pr = AdbManager.pair(context, host, p, code)
                                if (pr.isFailure) {
                                    error = context.getString(R.string.adb_error_pair_failed)
                                    info = null
                                    phase = AdbPhase.Idle
                                    return@launch
                                }
                                paired = true
                                phase = AdbPhase.Connecting
                                info = context.getString(R.string.adb_connecting)
                                val cr = AdbManager.connect(context)
                                connected = cr.isSuccess
                                info = null
                                phase = AdbPhase.Idle
                                if (!connected) {
                                    error = context.getString(R.string.adb_error_connect_failed)
                                }
                                onAdbReadyChanged()
                            }
                        },
                        enabled = !busy && code.length == 6,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.adb_btn_pair)) }
                }

                // Ya emparejado: reconexión + activación + toggle full-auto.
                if (paired) {
                    HorizontalDivider()

                    if (!serviceEnabled) {
                        // Activación inicial: encender el servicio sin pasar por
                        // el muro de "Ajustes restringidos".
                        Button(
                            onClick = {
                                error = null
                                scope.launch {
                                    phase = AdbPhase.Enabling
                                    info = context.getString(R.string.adb_connecting)
                                    val r = InitialEnable.enableAccessibilityViaAdb(context)
                                    info = null
                                    phase = AdbPhase.Idle
                                    if (r.isSuccess) onServiceEnabled()
                                    else error = context.getString(R.string.adb_error_connect_failed)
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.adb_btn_enable_now)) }
                    }

                    // Reconectar (tras un reinicio hay que re-activar Wireless
                    // debugging; el puerto cambia pero la clave sigue válida).
                    OutlinedButton(
                        onClick = {
                            error = null
                            scope.launch {
                                phase = AdbPhase.Connecting
                                info = context.getString(R.string.adb_connecting)
                                val cr = AdbManager.connect(context)
                                connected = cr.isSuccess
                                info = null
                                phase = AdbPhase.Idle
                                if (!connected) {
                                    error = context.getString(R.string.adb_error_connect_failed)
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.adb_btn_reconnect)) }

                    // Full-auto vía ADB (re-activación al cerrar app sensible).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.adb_fullauto_switch),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = fullAutoAdb,
                            onCheckedChange = { want ->
                                fullAutoAdb = want
                                Prefs.setFullAutoAdb(context, want)
                                onAdbReadyChanged()
                            },
                        )
                    }

                    Text(
                        text = stringResource(R.string.adb_reboot_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Progreso / errores.
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = info ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    text = stringResource(R.string.adb_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class InstalledApp(val pkg: String, val label: String, val builtin: Boolean)

private suspend fun loadInstalledApps(context: Context): List<InstalledApp> =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        // Apps que el usuario ve: todas las del cajón (ícono de lanzador). El
        // <queries> del manifest las hace visibles pese al filtrado de paquetes
        // de Android 11+. Los bancos/sensibles instalados también tienen
        // lanzador, así que caen aquí y se marcan con el flag builtin.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .asSequence()
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
            .map { pkg ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }
                InstalledApp(pkg, label, SensitiveApps.isBuiltinSensitive(pkg))
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensitiveAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var userApps by remember { mutableStateOf(Prefs.sensitiveUserApps(context)) }
    var excluded by remember { mutableStateOf(Prefs.sensitiveExcludedApps(context)) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = loadInstalledApps(context) }

    BackHandler { onBack() }

    fun isOn(app: InstalledApp): Boolean =
        app.pkg !in excluded && (app.builtin || app.pkg in userApps)

    fun toggle(app: InstalledApp) {
        if (isOn(app)) {
            userApps = userApps - app.pkg
            if (app.builtin) excluded = excluded + app.pkg
        } else {
            excluded = excluded - app.pkg
            if (!app.builtin) userApps = userApps + app.pkg
        }
        Prefs.setSensitiveUserApps(context, userApps)
        Prefs.setSensitiveExcludedApps(context, excluded)
    }

    val filtered = apps.filter { it.label.contains(query, ignoreCase = true) }
    val autoApps = filtered.filter { it.builtin }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bank_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.headlineSmall)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.bank_manage_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.bank_manage_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (apps.isEmpty()) {
                // Aún cargando la lista de apps (PackageManager es lento). Componer
                // el LazyColumn recién con los datos completos evita que Compose
                // antepon­ga la sección automática por encima del scroll (anchoring).
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (autoApps.isNotEmpty()) {
                        item(key = "header_auto") {
                            AppPickerSectionHeader(stringResource(R.string.bank_manage_section_auto))
                        }
                        items(autoApps, key = { "auto_" + it.pkg }) { app ->
                            AppPickerRow(app = app, checked = isOn(app), onToggle = { toggle(app) })
                        }
                    }
                    item(key = "header_all") {
                        AppPickerSectionHeader(stringResource(R.string.bank_manage_section_all))
                    }
                    items(filtered, key = { "all_" + it.pkg }) { app ->
                        AppPickerRow(app = app, checked = isOn(app), onToggle = { toggle(app) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun AppPickerRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CostCard() {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.cost_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.cost_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class Quad(val a: String, val b: String, val c: Color, val d: Color)

/** ¿Está habilitado nuestro AccessibilityService? */
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val me = "${context.packageName}/${VideoBoostService::class.java.name}"
    val meShort = "${context.packageName}/.${VideoBoostService::class.java.simpleName}"
    return enabled.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
}

/**
 * Abre Ajustes > Accesibilidad con la entrada de nuestro servicio resaltada.
 * Los extras `:settings:fragment_args_key` / `:settings:show_fragment_args`
 * hacen que la app de Settings de Pixel scrollee hasta la entrada y la
 * destaque brevemente.
 */
private fun openAccessibilitySettingsHighlighted(context: Context) {
    val component = ComponentName(context, VideoBoostService::class.java).flattenToString()
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        putExtra(":settings:fragment_args_key", component)
        putExtra(":settings:show_fragment_args", Bundle().apply {
            putString(":settings:fragment_args_key", component)
        })
    }
    context.startActivity(intent)
}
