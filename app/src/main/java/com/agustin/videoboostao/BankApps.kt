package com.agustin.videoboostao

/**
 * Apps bancarias/financieras que bloquean cualquier servicio de accesibilidad
 * activo (medida anti-fraude). Cuando una de estas pasa a primer plano, el
 * servicio se deshabilita solo (`disableSelf()`) para no disparar ese bloqueo.
 *
 * Lista sembrada con las apps detectadas en el dispositivo del usuario (MX) más
 * bancos comunes. Extensible: agregá el package de tu banco a [PACKAGES]. Los
 * [PREFIXES] cubren variantes (BET/retail, versiones regionales) con nombres
 * distintivos, para minimizar falsos positivos.
 */
object BankApps {

    private val PACKAGES = setOf(
        "com.nu.production",                 // Nu
        "com.citibanamex.banamexmobile",     // Citibanamex
        "mx.bancosantander.supermovil",      // Santander SuperMóvil
        "com.santander.enlace.BET",          // Santander Enlace
        "mx.klar.app",                       // Klar
        "com.mercadopago.wallet",            // Mercado Pago
        "com.digitalfemsa_spinplus",         // Spin by OXXO
        "com.bbva.bbvacontigo",              // BBVA México
        "com.banorte.bmovilmx",              // Banorte
        "com.hsbc.hsbcmexico",               // HSBC México
        "com.scotiabank.smx",                // Scotiabank
        "com.bancoazteca.bazdigital",        // Banco Azteca
        "mx.com.bancoppel.bancoppel",        // BanCoppel
        "com.hey.banco",                     // Hey Banco
        "mx.com.stori.app",                  // Stori
    )

    // Prefijos con nombre de banco distintivo (bajo riesgo de falso positivo).
    private val PREFIXES = listOf(
        "com.nu.",
        "com.bbva",
        "com.santander",
        "mx.bancosantander",
        "com.banorte",
        "com.citibanamex",
        "com.banamex",
        "com.hsbc.hsbc",
        "com.scotiabank",
        "com.mercadopago",
        "mx.klar",
        "com.hey.banco",
        "mx.com.stori",
        "com.bancoazteca",
        "mx.com.bancoppel",
    )

    fun isBank(pkg: String): Boolean =
        pkg in PACKAGES || PREFIXES.any { pkg.startsWith(it) }
}
