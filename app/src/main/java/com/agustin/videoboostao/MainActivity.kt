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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import kotlin.math.roundToInt

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

/**
 * Navegación por pantallas. La principal muestra solo el estado y las acciones
 * del día a día; todo lo demás (modo, apps sensibles, notificaciones, ADB) vive
 * en subpantallas para que un usuario nuevo no se encuentre diez tarjetas de
 * golpe.
 */
private enum class Screen { Main, Mode, Sensitive, AppPicker, Notifications, Advanced, About }

@Composable
private fun App() {
    var screen by remember { mutableStateOf(Screen.Main) }
    when (screen) {
        Screen.Main -> MainScreen(onNavigate = { screen = it })
        Screen.Mode -> ModeScreen(
            onBack = { screen = Screen.Main },
            onOpenAdvanced = { screen = Screen.Advanced },
        )
        Screen.Sensitive -> SensitiveScreen(
            onBack = { screen = Screen.Main },
            onManageApps = { screen = Screen.AppPicker },
        )
        Screen.AppPicker -> AppPickerScreen(onBack = { screen = Screen.Sensitive })
        Screen.Notifications -> NotificationsScreen(onBack = { screen = Screen.Main })
        Screen.Advanced -> AdvancedScreen(onBack = { screen = Screen.Main })
        Screen.About -> AboutScreen(onBack = { screen = Screen.Main })
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

// ---------------------------------------------------------------------------
// Pantalla principal
// ---------------------------------------------------------------------------

@Composable
private fun MainScreen(onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current

    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var mode by remember { mutableStateOf(Prefs.activationMode(context)) }
    var canOnDemand by remember { mutableStateOf(Capabilities.canRunOnDemand(context)) }
    var featureEnabled by remember { mutableStateOf(Prefs.featureEnabled(context)) }
    var disabledByBank by remember { mutableStateOf(Prefs.disabledByBank(context)) }
    // Izado y no consultado en cada recomposicion: isPaired toca el disco.
    var adbPaired by remember { mutableStateOf(AdbManager.isPaired(context)) }
    var fullAutoAdb by remember { mutableStateOf(Prefs.fullAutoAdb(context)) }
    var update by remember { mutableStateOf<UpdateChecker.Update?>(null) }

    LifecycleResumeEffect(Unit) {
        serviceEnabled = isAccessibilityServiceEnabled(context)
        mode = Prefs.activationMode(context)
        canOnDemand = Capabilities.canRunOnDemand(context)
        featureEnabled = Prefs.featureEnabled(context)
        disabledByBank = Prefs.disabledByBank(context)
        adbPaired = AdbManager.isPaired(context)
        fullAutoAdb = Prefs.fullAutoAdb(context)
        // El vigilante puede haber muerto (force-stop, actualización) o haberse
        // quedado corriendo con el modo ya cambiado: cada vez que la pantalla
        // vuelve al frente se re-sincroniza con lo que dicen las preferencias.
        CameraWatchService.sync(context)
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        update = UpdateChecker.check(context)
    }

    // Modo bajo demanda EN VIGOR: elegido y con los permisos puestos. Si
    // faltan, [Capabilities] degrada a siempre activo y el hero lo explica.
    val onDemand = mode == ActivationMode.ON_DEMAND && canOnDemand
    val onDemandPending = mode == ActivationMode.ON_DEMAND && !canOnDemand
    // "Listo para funcionar": en bajo demanda el servicio está apagado casi
    // siempre, así que mirar `serviceEnabled` diría siempre que no.
    val ready = onDemand || serviceEnabled

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

            HeroCard(
                serviceEnabled = serviceEnabled,
                featureEnabled = featureEnabled,
                onDemand = onDemand,
                onDemandPending = onDemandPending,
                available = ready,
                onToggle = { enabled ->
                    featureEnabled = enabled
                    Prefs.setFeatureEnabled(context, enabled)
                    if (mode == ActivationMode.ON_DEMAND) {
                        CameraWatchService.sync(context)
                        // Pausar en bajo demanda no debe dejar el servicio de
                        // accesibilidad encendido sin nadie que lo apague.
                        if (!enabled && SecureSettings.disableOwnAccessibilityService(context)) {
                            serviceEnabled = false
                        }
                    }
                },
                onFixMode = { onNavigate(Screen.Mode) },
            )

            if (ready) {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent("android.media.action.VIDEO_CAMERA")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(
                        text = stringResource(R.string.btn_try_now),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            } else {
                // Falta configurar: el asistente guiado es LO ÚNICO importante.
                SetupCard(context, serviceEnabled = false, disabledByBank = disabledByBank)
            }

            if (ready) {
                Text(
                    text = stringResource(R.string.settings_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
                NavList(
                    listOf(
                        NavItem(
                            title = stringResource(R.string.mode_card_title),
                            subtitle = stringResource(
                                if (onDemand) R.string.mode_ondemand_title
                                else R.string.mode_always_title
                            ),
                            onClick = { onNavigate(Screen.Mode) },
                        ),
                        NavItem(
                            title = stringResource(R.string.bank_card_title),
                            subtitle = stringResource(
                                if (onDemand) R.string.nav_sensitive_sub_ondemand
                                else R.string.nav_sensitive_sub
                            ),
                            onClick = { onNavigate(Screen.Sensitive) },
                        ),
                        NavItem(
                            title = stringResource(R.string.btn_notifications),
                            subtitle = stringResource(R.string.nav_notifications_sub),
                            onClick = { onNavigate(Screen.Notifications) },
                        ),
                        NavItem(
                            title = stringResource(R.string.nav_advanced_title),
                            subtitle = stringResource(
                                when {
                                    fullAutoAdb && adbPaired -> R.string.adb_status_ready
                                    adbPaired -> R.string.adb_status_paired_disconnected
                                    else -> R.string.adb_status_off
                                }
                            ),
                            onClick = { onNavigate(Screen.Advanced) },
                        ),
                        NavItem(
                            title = stringResource(R.string.nav_about_title),
                            subtitle = stringResource(R.string.nav_about_sub),
                            onClick = { onNavigate(Screen.About) },
                        ),
                    )
                )
                SupportCard(context)
                // Guía consultable una vez configurado: colapsada, al final.
                SetupCard(context, serviceEnabled = true, disabledByBank = disabledByBank)
            } else {
                // Sin configurar solo se ofrece lo que ayuda a decidir y
                // activar: la alternativa ADB y la transparencia (qué hace
                // exactamente la app y su código abierto) ANTES de conceder
                // el permiso de accesibilidad. El resto de ajustes aparece
                // cuando la app ya funciona.
                NavList(
                    listOf(
                        NavItem(
                            title = stringResource(R.string.setup_adb_row_title),
                            subtitle = stringResource(R.string.adb_status_off),
                            onClick = { onNavigate(Screen.Advanced) },
                        ),
                        NavItem(
                            title = stringResource(R.string.nav_about_title),
                            subtitle = stringResource(R.string.nav_about_sub),
                            onClick = { onNavigate(Screen.About) },
                        ),
                    )
                )
            }

            // Pie: avisos que conviene conocer pero no merecen tarjeta.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.cost_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.cost_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.pro_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Estado + interruptor maestro en una sola tarjeta. Antes eran dos: fundirlas
 * quita una tarjeta de la pantalla y deja claro que el interruptor gobierna lo
 * que el estado describe.
 */
@Composable
private fun HeroCard(
    serviceEnabled: Boolean,
    featureEnabled: Boolean,
    onDemand: Boolean,
    onDemandPending: Boolean,
    available: Boolean,
    onToggle: (Boolean) -> Unit,
    onFixMode: () -> Unit,
) {
    val (title, body, container, onContainer) = when {
        // Bajo demanda elegido pero sin los permisos: no está roto, está a
        // medio configurar, y hasta entonces corre en modo siempre activo.
        onDemandPending -> Quad(
            stringResource(R.string.status_setup_title),
            stringResource(R.string.status_ondemand_setup_body),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        // En bajo demanda el servicio está apagado casi siempre: leer
        // `serviceEnabled` diría "falta configurar" para siempre.
        onDemand && featureEnabled -> Quad(
            stringResource(R.string.status_ondemand_title),
            stringResource(R.string.status_ondemand_body),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        onDemand -> Quad(
            stringResource(R.string.status_paused_title),
            stringResource(R.string.status_paused_body),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

    // Transición suave de color al cambiar de estado (Expressive: el estado se
    // siente vivo en vez de saltar de golpe).
    val bg by animateColorAsState(container, label = "heroBg")
    val fg by animateColorAsState(onContainer, label = "heroFg")

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = bg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF6CC0FE),
                    modifier = Modifier.size(56.dp),
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = fg,
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = fg,
            )
            if (onDemandPending) {
                FilledTonalButton(onClick = onFixMode, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.hero_fix_mode))
                }
            }
            if (available) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = fg.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.master_switch_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = fg,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = featureEnabled, onCheckedChange = onToggle)
                    }
                }
            }
        }
    }
}

/** Fila de navegación a una subpantalla. */
private data class NavItem(val title: String, val subtitle: String?, val onClick: () -> Unit)

@Composable
private fun NavList(items: List<NavItem>) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            items.forEachIndexed { i, item ->
                if (i > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = item.onClick)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        item.subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Apoyo al proyecto: prominente pero no invasivo. Una tarjeta cálida fija al
 * final de la pantalla principal — sin popups, sin cuenta atrás, sin insistir.
 * Solo aparece cuando la app ya funciona: pedir apoyo antes de servir es de
 * mal gusto.
 */
@Composable
private fun SupportCard(context: Context) {
    if (DonationConfig.DONATE_URL.isBlank()) return
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.support_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = stringResource(R.string.support_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            FilledTonalButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(DonationConfig.DONATE_URL))
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("☕  " + stringResource(R.string.btn_donate))
            }
        }
    }
}

/** Andamiaje común de las subpantallas: barra con título + volver, y columna
 *  scrolleable con el espaciado de la principal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Guía de configuración
// ---------------------------------------------------------------------------

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
                modifier = Modifier
                    .padding(20.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setup_card_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
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

// ---------------------------------------------------------------------------
// Subpantalla: modo de activación
// ---------------------------------------------------------------------------

/**
 * Elección del modo de activación. Las dos opciones son válidas y ninguna es
 * "la buena": siempre activo funciona en cualquier teléfono, bajo demanda es más
 * discreto pero pide los permisos especiales.
 *
 * Bajo demanda se puede elegir SIN tener los permisos: la app degrada sola a
 * siempre activo ([Capabilities.activationMode]) y acá se explica qué falta. Es
 * mejor que un botón deshabilitado sin explicación.
 */
@Composable
private fun ModeScreen(onBack: () -> Unit, onOpenAdvanced: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(Prefs.activationMode(context)) }
    var usageAccess by remember { mutableStateOf(Capabilities.hasUsageAccess(context)) }
    var localSecure by remember { mutableStateOf(Capabilities.canWriteSecureSettings(context)) }
    var adbPaired by remember { mutableStateOf(AdbManager.isPaired(context)) }
    var linger by remember { mutableStateOf(Prefs.onDemandLingerSec(context)) }
    var granting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val canOnDemand = usageAccess && localSecure

    LifecycleResumeEffect(Unit) {
        usageAccess = Capabilities.hasUsageAccess(context)
        localSecure = Capabilities.canWriteSecureSettings(context)
        adbPaired = AdbManager.isPaired(context)
        onPauseOrDispose { }
    }

    fun select(wanted: ActivationMode) {
        mode = wanted
        Prefs.setActivationMode(context, wanted)
        CameraWatchService.sync(context)
        if (wanted == ActivationMode.ON_DEMAND) {
            // El usuario está mirando esta pantalla, o sea que la cámara no está
            // abierta: apagar ya en vez de esperar a que el vigilante confirme
            // la salida y cumpla la gracia.
            SecureSettings.disableOwnAccessibilityService(context)
        } else {
            // Volver a siempre activo lo reenciende sin pasar por Ajustes: el
            // permiso de escritura local ya está puesto.
            SecureSettings.enableOwnAccessibilityService(context)
        }
    }

    SubScreen(title = stringResource(R.string.mode_card_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.mode_card_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ModeOptionCard(
            selected = mode == ActivationMode.ALWAYS,
            title = stringResource(R.string.mode_always_title),
            body = stringResource(R.string.mode_always_body),
            onClick = { select(ActivationMode.ALWAYS) },
        )
        ModeOptionCard(
            selected = mode == ActivationMode.ON_DEMAND,
            title = stringResource(R.string.mode_ondemand_title),
            body = stringResource(R.string.mode_ondemand_body),
            onClick = { select(ActivationMode.ON_DEMAND) },
        )

        if (mode == ActivationMode.ON_DEMAND) {
            if (canOnDemand) {
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
                            text = stringResource(R.string.mode_ondemand_ready),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.mode_linger_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (linger == 0) {
                                    stringResource(R.string.mode_linger_immediate)
                                } else {
                                    stringResource(R.string.mode_linger_value, linger)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = linger.toFloat(),
                            // El estado se mueve en cada frame y en Prefs se
                            // escribe una sola vez, al soltar.
                            onValueChange = { linger = (it / 5f).roundToInt() * 5 },
                            onValueChangeFinished = {
                                Prefs.setOnDemandLingerSec(context, linger)
                            },
                            valueRange = 0f..Prefs.MAX_LINGER_SEC.toFloat(),
                            // Pasos de 5 s: paradas en 0, 5, 10... 60, o sea 11
                            // valores y 10 intermedios.
                            steps = (Prefs.MAX_LINGER_SEC / 5) - 1,
                        )
                        Text(
                            text = stringResource(R.string.mode_linger_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
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
                            text = stringResource(R.string.mode_ondemand_locked),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!usageAccess) {
                            Text(
                                text = stringResource(R.string.mode_ondemand_missing_usage),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (!localSecure) {
                            Text(
                                text = stringResource(R.string.mode_ondemand_missing_secure),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(
                            onClick = {
                                failed = false
                                granting = true
                                scope.launch {
                                    // Una sola conexión ADB concede los dos
                                    // permisos (ver [InitialEnable]). Sin
                                    // emparejar falla y el mensaje manda a la
                                    // pantalla de ADB.
                                    val ok = InitialEnable.grantFullAutoPermissionsViaAdb(context)
                                    granting = false
                                    failed = !ok
                                    usageAccess = Capabilities.hasUsageAccess(context)
                                    localSecure = Capabilities.canWriteSecureSettings(context)
                                    adbPaired = AdbManager.isPaired(context)
                                    CameraWatchService.sync(context)
                                }
                            },
                            enabled = !granting && adbPaired,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (granting) R.string.mode_granting else R.string.mode_grant_now,
                                )
                            )
                        }
                        if (!usageAccess) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.fullauto_grant_usage)) }
                        }
                        if (failed || !adbPaired) {
                            Text(
                                text = stringResource(R.string.mode_grant_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = onOpenAdvanced,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.mode_open_advanced)) }
                        }
                    }
                }
            }
        }
    }
}

/** Opción de modo como tarjeta seleccionable (Expressive), en vez de una fila
 *  de radio apretada: más área de toque y estado seleccionado evidente. */
@Composable
private fun ModeOptionCard(
    selected: Boolean,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Subpantalla: apps sensibles
// ---------------------------------------------------------------------------

@Composable
private fun SensitiveScreen(onBack: () -> Unit, onManageApps: () -> Unit) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var onDemandActive by remember { mutableStateOf(Capabilities.onDemandMode(context)) }
    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var autoDisable by remember { mutableStateOf(Prefs.autoDisableForBanks(context)) }
    var reenableDelay by remember { mutableStateOf(Prefs.reenableDelayMin(context)) }
    var fullAuto by remember { mutableStateOf(Prefs.fullAutoShizuku(context)) }
    var shizukuAvailable by remember { mutableStateOf(ShizukuManager.isAvailable()) }
    var shizukuReady by remember { mutableStateOf(ShizukuManager.hasPermission()) }
    var usageAccess by remember { mutableStateOf(Capabilities.hasUsageAccess(context)) }
    var adbReady by remember { mutableStateOf(Capabilities.adbReady(context)) }
    var localSecure by remember { mutableStateOf(Capabilities.canWriteSecureSettings(context)) }

    LifecycleResumeEffect(Unit) {
        onDemandActive = Capabilities.onDemandMode(context)
        serviceEnabled = isAccessibilityServiceEnabled(context)
        autoDisable = Prefs.autoDisableForBanks(context)
        reenableDelay = Prefs.reenableDelayMin(context)
        fullAuto = Prefs.fullAutoShizuku(context)
        shizukuAvailable = ShizukuManager.isAvailable()
        shizukuReady = ShizukuManager.hasPermission()
        usageAccess = Capabilities.hasUsageAccess(context)
        adbReady = Capabilities.adbReady(context)
        localSecure = Capabilities.canWriteSecureSettings(context)
        onPauseOrDispose { }
    }

    SubScreen(title = stringResource(R.string.bank_card_title), onBack = onBack) {
        if (onDemandActive) {
            // Toda la maquinaria de apps sensibles sobra acá: el servicio solo
            // existe dentro de la cámara y ningún banco lo ve.
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
                        text = stringResource(R.string.bank_not_needed_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.bank_not_needed_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.bank_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Sin capacidad de reactivarse solo no hay nada que demorar: el
            // usuario reactiva a mano y la espera la pone él. Mismo criterio que
            // [Capabilities.canReenableItself], pero sobre el estado ya izado
            // para que recomponga solo.
            val canReenable = localSecure || adbReady || (fullAuto && shizukuReady)

            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.bank_auto_switch),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = autoDisable,
                            onCheckedChange = { enabled ->
                                autoDisable = enabled
                                Prefs.setAutoDisableForBanks(context, enabled)
                            },
                        )
                    }
                    if (autoDisable && canReenable) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.bank_delay_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (reenableDelay == 0) {
                                    stringResource(R.string.bank_delay_immediate)
                                } else {
                                    stringResource(R.string.bank_delay_value, reenableDelay)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = reenableDelay.toFloat(),
                            // El slider avisa en cada frame del arrastre: el
                            // estado se actualiza siempre (es barato) y en Prefs
                            // se escribe una sola vez, al soltar.
                            onValueChange = { reenableDelay = it.roundToInt() },
                            onValueChangeFinished = {
                                Prefs.setReenableDelayMin(context, reenableDelay)
                            },
                            valueRange = 0f..Prefs.MAX_REENABLE_DELAY_MIN.toFloat(),
                            // `steps` son los valores INTERMEDIOS: 9 da paradas 0..10.
                            steps = Prefs.MAX_REENABLE_DELAY_MIN - 1,
                        )
                        Text(
                            text = stringResource(R.string.bank_delay_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FilledTonalButton(onClick = onManageApps, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bank_manage_apps))
            }
            if (serviceEnabled) {
                OutlinedButton(
                    onClick = {
                        VideoBoostService.instance?.disableSelf()
                        serviceEnabled = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.bank_turn_off_now))
                }
            }

            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    FullAutoSection(
                        fullAuto = fullAuto,
                        shizukuAvailable = shizukuAvailable,
                        shizukuReady = shizukuReady,
                        usageAccess = usageAccess,
                        adbReady = adbReady,
                        localSecure = localSecure,
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
            }
        }
    }
}

/**
 * Sección "Full-auto y recordatorios" dentro de la pantalla de apps sensibles.
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
    localSecure: Boolean,
    onToggleFullAuto: (Boolean) -> Unit,
    onGrantShizuku: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    onOpenShizukuSite: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.animateContentSize(),
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

            // Estado de la reactivación local: es lo que evita abrir ADB (y que
            // el sistema muestre su notificación de depuración inalámbrica).
            Text(
                text = stringResource(
                    if (localSecure) R.string.local_secure_ready else R.string.local_secure_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (localSecure) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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

// ---------------------------------------------------------------------------
// Subpantalla: ADB inalámbrico (avanzado)
// ---------------------------------------------------------------------------

/** Fases del emparejamiento ADB inalámbrico. */
private enum class AdbPhase { Idle, Discovering, AwaitingCode, Pairing, Connecting, Enabling }

/**
 * Pantalla de la alternativa in-app a Shizuku: empareja la app con el "Wireless
 * debugging" del propio dispositivo (ADB inalámbrico) y, con eso, puede activar
 * el servicio de accesibilidad la primera vez y re-activarlo solo (full-auto)
 * al cerrarse una app sensible. No sustituye a Shizuku; es una vía paralela.
 */
@Composable
private fun AdvancedScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var paired by remember { mutableStateOf(AdbManager.isPaired(context)) }
    var fullAutoAdb by remember { mutableStateOf(Prefs.fullAutoAdb(context)) }
    var localSecure by remember { mutableStateOf(Capabilities.canWriteSecureSettings(context)) }
    var connected by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(AdbPhase.Idle) }
    var code by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var waitingNotif by remember { mutableStateOf(false) }
    var manualExpanded by remember { mutableStateOf(false) }

    // Al volver de Ajustes (p.ej. tras emparejar por la notificación) refrescar
    // el estado de emparejado/full-auto.
    LifecycleResumeEffect(Unit) {
        serviceEnabled = isAccessibilityServiceEnabled(context)
        paired = AdbManager.isPaired(context)
        fullAutoAdb = Prefs.fullAutoAdb(context)
        localSecure = Capabilities.canWriteSecureSettings(context)
        onPauseOrDispose { }
    }

    val busy = phase != AdbPhase.Idle

    SubScreen(title = stringResource(R.string.nav_advanced_title), onBack = onBack) {
        Text(
            text = stringResource(
                when {
                    fullAutoAdb && paired -> R.string.adb_status_ready
                    paired -> R.string.adb_status_paired_disconnected
                    else -> R.string.adb_status_off
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.adb_section_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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

                // Paso 2: emparejar por NOTIFICACIÓN (estilo Shizuku). El
                // usuario no sale de la pantalla de Depuración inalámbrica: la
                // app escucha por mDNS y le manda una notificación con campo de
                // respuesta para teclear el código ahí mismo.
                StepBlock(
                    number = 2,
                    title = stringResource(R.string.adb_step2_title),
                    body = stringResource(R.string.adb_step2_body),
                ) {
                    Button(
                        onClick = {
                            error = null
                            waitingNotif = true
                            AdbPairingService.start(context)
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.adb_btn_start_pairing)) }
                    if (waitingNotif) {
                        Text(
                            text = stringResource(R.string.adb_waiting_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Fallback manual (avanzado): teclear código + host:puerto
                    // acá. Solo sirve en ROMs que mantienen vivo el pairing al
                    // salir de la pantalla; por eso es secundario.
                    TextButton(onClick = { manualExpanded = !manualExpanded }) {
                        Text(
                            stringResource(
                                if (manualExpanded) R.string.adb_manual_hide
                                else R.string.adb_manual_show
                            )
                        )
                    }
                    if (manualExpanded) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text(stringResource(R.string.adb_code_label)) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                                    localSecure = Capabilities.canWriteSecureSettings(context)
                                }
                            },
                            enabled = !busy && code.length == 6,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.adb_btn_pair)) }
                    }
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
                                    if (r.isSuccess) {
                                        serviceEnabled = isAccessibilityServiceEnabled(context)
                                    } else {
                                        error = context.getString(R.string.adb_error_connect_failed)
                                    }
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
                                // Concederse ya los dos permisos que hacen que el
                                // full-auto no dependa de ADB en caliente: sondeo
                                // local del primer plano y escritura local para
                                // reactivar (ver BankWatchService y SecureSettings).
                                // Al apagar se devuelve el "Acceso de uso" —el de
                                // escritura no, ver PrivilegedShell—.
                                scope.launch {
                                    if (want) InitialEnable.grantFullAutoPermissionsViaAdb(context)
                                    else InitialEnable.revokeUsageAccessViaAdb(context)
                                    localSecure = Capabilities.canWriteSecureSettings(context)
                                }
                            },
                        )
                    }

                    Text(
                        text = stringResource(
                            if (localSecure) {
                                R.string.local_secure_ready
                            } else {
                                R.string.local_secure_hint
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (localSecure) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )

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
            }
        }

        Text(
            text = stringResource(R.string.adb_attribution),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Subpantalla: acerca de, privacidad y código abierto
// ---------------------------------------------------------------------------

/**
 * Transparencia total, en la propia app: qué hace exactamente, qué no hace
 * nunca, cada permiso explicado en cristiano, por qué no está en la Play Store
 * y el enlace al código fuente. Es la respuesta a la incomodidad legítima que
 * genera conceder un servicio de accesibilidad: que nadie tenga que confiar a
 * ciegas.
 */
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
    }

    SubScreen(title = stringResource(R.string.nav_about_title), onBack = onBack) {
        AboutBlock(
            title = stringResource(R.string.about_what_title),
            body = stringResource(R.string.about_what_body),
        )
        AboutBlock(
            title = stringResource(R.string.about_never_title),
            body = stringResource(R.string.about_never_body),
        )

        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_open_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(R.string.about_open_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                FilledTonalButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(DonationConfig.REPO_URL))
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_view_source)) }
            }
        }

        Text(
            text = stringResource(R.string.about_perms_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PermRow(
                    title = stringResource(R.string.perm_a11y_title),
                    body = stringResource(R.string.perm_a11y_body),
                )
                PermRow(
                    title = stringResource(R.string.perm_internet_title),
                    body = stringResource(R.string.perm_internet_body),
                )
                PermRow(
                    title = stringResource(R.string.perm_notif_title),
                    body = stringResource(R.string.perm_notif_body),
                )
                PermRow(
                    title = stringResource(R.string.perm_fgs_title),
                    body = stringResource(R.string.perm_fgs_body),
                )
                PermRow(
                    title = stringResource(R.string.perm_usage_title),
                    body = stringResource(R.string.perm_usage_body),
                )
                PermRow(
                    title = stringResource(R.string.perm_secure_title),
                    body = stringResource(R.string.perm_secure_body),
                )
            }
        }

        AboutBlock(
            title = stringResource(R.string.about_play_title),
            body = stringResource(R.string.about_play_body),
        )

        Text(
            text = stringResource(R.string.about_version, versionName) + " · MIT",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutBlock(title: String, body: String) {
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermRow(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Subpantalla: selector de apps sensibles
// ---------------------------------------------------------------------------

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
private fun AppPickerScreen(onBack: () -> Unit) {
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

// ---------------------------------------------------------------------------
// Subpantalla: notificaciones
// ---------------------------------------------------------------------------

/**
 * Control de notificaciones. Dos clases de fila, y la diferencia no es
 * caprichosa:
 *
 * - **Opcionales**: tienen interruptor y, apagadas, no se postean nunca (ver
 *   [Notifications]).
 * - **Obligatorias**: son la notificación de un foreground service, que Android
 *   exige mientras el servicio corre. No hay forma de ocultarlas; lo que sí se
 *   puede es silenciarlas, con el maestro de arriba o desde el canal del
 *   sistema. Mentir con un interruptor que no apaga nada sería peor.
 *
 * El sonido, la vibración y la importancia viven en los canales, que son
 * inmutables desde código: por eso cada fila lleva un atajo a sus ajustes en vez
 * de controles propios.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var silenceAll by remember { mutableStateOf(Prefs.silenceAllNotifs(context)) }
    var serviceOff by remember { mutableStateOf(Prefs.notifServiceOff(context)) }
    var closed by remember { mutableStateOf(Prefs.notifClosed(context)) }
    var reenabled by remember { mutableStateOf(Prefs.notifReenabled(context)) }
    var activated by remember { mutableStateOf(Prefs.notifActivated(context)) }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notif_screen_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.notif_screen_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            NotifRow(
                title = stringResource(R.string.notif_silence_title),
                desc = stringResource(R.string.notif_silence_desc),
                checked = silenceAll,
                onCheckedChange = {
                    silenceAll = it
                    Prefs.setSilenceAllNotifs(context, it)
                },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            NotifRow(
                title = stringResource(R.string.notif_row_service_off_title),
                desc = stringResource(R.string.notif_row_service_off_desc),
                checked = serviceOff,
                onCheckedChange = {
                    serviceOff = it
                    Prefs.setNotifServiceOff(context, it)
                },
                channelId = Notifications.CHANNEL_REMINDER_SILENT,
            )
            NotifRow(
                title = stringResource(R.string.notif_row_closed_title),
                desc = stringResource(R.string.notif_row_closed_desc),
                checked = closed,
                onCheckedChange = {
                    closed = it
                    Prefs.setNotifClosed(context, it)
                },
                channelId = Notifications.CHANNEL_ALERT,
            )
            NotifRow(
                title = stringResource(R.string.notif_row_reenabled_title),
                desc = stringResource(R.string.notif_row_reenabled_desc),
                checked = reenabled,
                onCheckedChange = {
                    reenabled = it
                    Prefs.setNotifReenabled(context, it)
                },
                channelId = Notifications.CHANNEL_ALERT,
            )
            NotifRow(
                title = stringResource(R.string.notif_row_activated_title),
                desc = stringResource(R.string.notif_row_activated_desc),
                checked = activated,
                onCheckedChange = {
                    activated = it
                    Prefs.setNotifActivated(context, it)
                },
                channelId = Notifications.CHANNEL_ACTIVATED,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            NotifRow(
                title = stringResource(R.string.notif_row_paused_title),
                desc = stringResource(R.string.notif_row_paused_desc),
                checked = null,
                channelId = Notifications.CHANNEL_PAUSED,
            )
            NotifRow(
                title = stringResource(R.string.notif_row_countdown_title),
                desc = stringResource(R.string.notif_row_countdown_desc),
                checked = null,
                channelId = Notifications.CHANNEL_ALERT,
            )
            NotifRow(
                title = stringResource(R.string.notif_row_watch_title),
                desc = stringResource(R.string.notif_row_watch_desc),
                checked = null,
                channelId = Notifications.CHANNEL_WATCH,
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { context.startActivity(Notifications.appSettingsIntent(context)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.notif_open_all)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Una fila de la pantalla de notificaciones. [checked] a null significa
 * "obligatoria": sin interruptor y con la nota que lo explica.
 */
@Composable
private fun NotifRow(
    title: String,
    desc: String,
    checked: Boolean?,
    onCheckedChange: (Boolean) -> Unit = {},
    channelId: String? = null,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (checked == null) {
                    Text(
                        text = stringResource(R.string.notif_required_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (checked != null) {
                Spacer(Modifier.width(12.dp))
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
        }
        if (channelId != null) {
            TextButton(
                onClick = {
                    context.startActivity(Notifications.channelSettingsIntent(context, channelId))
                },
            ) { Text(stringResource(R.string.notif_channel_action)) }
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
