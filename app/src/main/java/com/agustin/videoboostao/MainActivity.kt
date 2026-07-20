package com.agustin.videoboostao

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen()
            }
        }
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
private fun MainScreen() {
    val context = LocalContext.current

    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var featureEnabled by remember { mutableStateOf(Prefs.featureEnabled(context)) }
    var update by remember { mutableStateOf<UpdateChecker.Update?>(null) }

    LifecycleResumeEffect(Unit) {
        serviceEnabled = isAccessibilityServiceEnabled(context)
        featureEnabled = Prefs.featureEnabled(context)
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

            // Guía de configuración: siempre visible. Expandida cuando falta
            // configurar; colapsada (consultable) cuando el servicio ya está activo.
            SetupCard(context, serviceEnabled)

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
                color = Color(0xFF55BFEF),
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
private fun SetupCard(context: Context, serviceEnabled: Boolean) {
    var expanded by remember(serviceEnabled) { mutableStateOf(!serviceEnabled) }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (serviceEnabled) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.setup_card_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (serviceEnabled) {
                        Text(
                            text = stringResource(R.string.setup_done_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                NumberedStep(1, stringResource(R.string.setup_step_1))
                NumberedStep(2, stringResource(R.string.setup_step_2))
                NumberedStep(3, stringResource(R.string.setup_step_3))

                Button(
                    onClick = { openAccessibilitySettingsHighlighted(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.btn_open_accessibility))
                }
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
                ) {
                    Text(stringResource(R.string.btn_open_app_info))
                }
            }
        }
    }
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(24.dp),
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.padding(top = 3.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
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
