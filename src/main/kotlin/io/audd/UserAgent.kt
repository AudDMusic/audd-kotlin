package io.audd

internal const val SDK_VERSION: String = "0.1.0"

internal fun userAgent(): String {
    val kotlin = KotlinVersion.CURRENT
    val javaVersion = System.getProperty("java.version") ?: "unknown"
    return "audd-kotlin/$SDK_VERSION (kotlin $kotlin; java $javaVersion)"
}
