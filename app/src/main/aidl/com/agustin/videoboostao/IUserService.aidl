// Interfaz del UserService de Shizuku. La implementación corre en un proceso
// aparte con identidad shell (UID 2000), fuera del sandbox de la app.
package com.agustin.videoboostao;

interface IUserService {
    // id reservado por Shizuku para destruir el servicio.
    void destroy() = 16777114;

    // Package en primer plano (parseado de `dumpsys activity activities`).
    String getForegroundPackage() = 1;

    // Agrega `component` a enabled_accessibility_services y enciende a11y.
    boolean enableAccessibilityService(String component) = 2;

    // Concede GET_USAGE_STATS a `pkg` para poder leer el primer plano en local.
    boolean grantUsageAccess(String pkg) = 3;

    // Devuelve GET_USAGE_STATS de `pkg` a su valor por defecto.
    boolean revokeUsageAccess(String pkg) = 4;
}
