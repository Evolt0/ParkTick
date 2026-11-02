package com.evolto.parktick.data

import java.text.Normalizer
import java.util.Locale

object PlateText {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
        .uppercase(Locale.ROOT)

    fun searchPattern(value: String): String = buildString {
        append('%')
        for (character in normalize(value)) {
            if (character == '\\' || character == '%' || character == '_') append('\\')
            append(character)
        }
        append('%')
    }
}
