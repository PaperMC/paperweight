package io.papermc.paperweight.util

interface AsmUtil {
    operator fun Int.contains(value: Int): Boolean {
        return value and this == value
    }
}
