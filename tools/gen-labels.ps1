# Regenera CameraLabels.kt desde el dump de recursos de Pixel Camera.
#
# Uso:
#   1. adb shell pm path com.google.android.GoogleCamera   # ubicar base.apk
#   2. adb pull <ruta base.apk>  <repo>\tools\gcam-base.apk
#   3. <sdk>\build-tools\<ver>\aapt2 dump resources tools\gcam-base.apk > tools\gcam-resources.txt
#   4. powershell tools\gen-labels.ps1
#
# Ajustar $dump y $outFile si cambian las rutas.

$here    = Split-Path -Parent $MyInvocation.MyCommand.Path
$dump    = Join-Path $here "gcam-resources.txt"
$outFile = Join-Path (Split-Path -Parent $here) "app\src\main\java\com\agustin\videoboostao\CameraLabels.kt"
$lines   = Get-Content $dump -Encoding UTF8

function Get-ResourceBlock($resourceName) {
    $i = ($lines | Select-String -Pattern ("resource 0x[0-9a-f]+ string/" + $resourceName + "$")).LineNumber
    $entries = @{}
    for ($j = $i; $j -lt $lines.Count; $j++) {
        $line = $lines[$j].Trim()
        if ($line -match '^resource 0x' -and $j -gt $i) { break }
        if ($line -match '^\(([^)]*)\)\s+"(.*)"$') {
            $locale = $Matches[1]; $value = $Matches[2]
            # Pseudo-locales fuera
            if ($locale -eq 'en-rXA' -or $locale -eq 'ar-rXB') { continue }
            # Normalizar locale -> codigo de idioma
            if ($locale -eq '') { $lang = 'en' }
            elseif ($locale -like 'b+*') { $lang = ($locale -split '\+')[1] }  # b+sr+Latn -> sr
            else { $lang = ($locale -split '-')[0] }
            if (-not $entries.ContainsKey($lang)) { $entries[$lang] = New-Object System.Collections.ArrayList }
            if (-not $entries[$lang].Contains($value)) { [void]$entries[$lang].Add($value) }
        }
    }
    return $entries
}

$boost = Get-ResourceBlock 'sapphire_label'
$mode  = Get-ResourceBlock 'mode_video'
$on    = Get-ResourceBlock 'sapphire_on_desc'

function Emit-Map($name, $entries) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("    val ${name}: Map<String, List<String>> = mapOf(")
    foreach ($lang in ($entries.Keys | Sort-Object)) {
        $vals = ($entries[$lang] | ForEach-Object { '"' + ($_ -replace '\\', '\\' -replace '"', '\"') + '"' }) -join ', '
        [void]$sb.AppendLine("        `"$lang`" to listOf($vals),")
    }
    [void]$sb.AppendLine("    )")
    return $sb.ToString()
}

$header = @'
package com.agustin.videoboostao

/**
 * Etiquetas localizadas de Pixel Camera, extraidas automaticamente del
 * base.apk del dispositivo con `aapt2 dump resources`:
 * - string/sapphire_label   -> fila "Video Boost" del panel de ajustes
 * - string/mode_video       -> chip "Video" del carrusel de modos
 * - string/sapphire_on_desc -> contentDescription del boton "on"
 * NO editar a mano: regenerar con tools/gen-labels.ps1 tras cada Feature
 * Drop que cambie las cadenas.
 *
 * Clave = codigo de idioma (legacy de java.util.Locale: "in", "iw"...).
 * Las variantes regionales se fusionan como candidatos del mismo idioma
 * (p. ej. "es" incluye a "Optimizador de video" de Espana y "Video
 * mejorado" de es-US).
 */
object CameraLabels {

'@

$footer = @'

    /** Alias de codigos modernos -> legacy usados por Locale.getLanguage(). */
    private val LANG_ALIASES = mapOf("id" to "in", "he" to "iw", "fil" to "tl", "no" to "nb")

    private fun normalize(lang: String): String = LANG_ALIASES[lang] ?: lang

    /** Candidatos para la fila Video Boost: idioma del sistema + fallback en ingles. */
    fun videoBoostLabels(lang: String): List<String> {
        val own = VIDEO_BOOST_BY_LANG[normalize(lang)] ?: emptyList()
        val en = VIDEO_BOOST_BY_LANG.getValue("en")
        return (own + en).distinct()
    }

    /** Candidatos para el chip de modo Video: idioma del sistema + fallback en ingles. */
    fun videoModeLabels(lang: String): List<String> {
        val own = VIDEO_MODE_BY_LANG[normalize(lang)] ?: emptyList()
        val en = VIDEO_MODE_BY_LANG.getValue("en")
        return (own + en).distinct()
    }

    /** contentDescription del boton "on" (sapphire_on_desc, p. ej. "Video
     *  Boost on"): identifica el boton correcto por significado, sin depender
     *  de la posicion — imprescindible en layouts RTL donde el orden se invierte. */
    fun videoBoostOnDescs(lang: String): List<String> {
        val own = VIDEO_BOOST_ON_BY_LANG[normalize(lang)] ?: emptyList()
        val en = VIDEO_BOOST_ON_BY_LANG.getValue("en")
        return (own + en).distinct()
    }
}
'@

$body = (Emit-Map 'VIDEO_BOOST_BY_LANG' $boost) + "`r`n" + (Emit-Map 'VIDEO_MODE_BY_LANG' $mode) + "`r`n" + (Emit-Map 'VIDEO_BOOST_ON_BY_LANG' $on)
$content = $header + $body + $footer
[System.IO.File]::WriteAllText($outFile, $content, (New-Object System.Text.UTF8Encoding($false)))
"Idiomas Video Boost: $($boost.Keys.Count) | mode Video: $($mode.Keys.Count) | on-desc: $($on.Keys.Count)"
"Escrito: $outFile"
