package com.miro.a11y.util

import java.util.regex.Pattern

/**
 * Pure parser for "IP:port" strings. Used by the accessibility service
 * to extract the wireless debug address from a system dialog.
 */
object IpPortParser {
    private val PATTERN: Pattern = Pattern.compile(
        "(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{2,5})"
    )

    data class Result(val ip: String, val port: Int)

    fun parse(text: String?): Result? {
        if (text.isNullOrBlank()) return null
        val m = PATTERN.matcher(text)
        if (!m.find()) return null
        val ip = m.group(1) ?: return null
        val portStr = m.group(2) ?: return null
        val port = portStr.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Result(ip, port)
    }
}
