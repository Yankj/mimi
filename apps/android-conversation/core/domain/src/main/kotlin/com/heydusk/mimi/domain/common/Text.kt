package com.heydusk.mimi.domain.common

import java.text.BreakIterator
import java.util.Locale

/**
 * Swift `String.count` 数的是字形簇（Character），Kotlin `length` 数的是 UTF-16 单元。
 * 凡是移植 iOS 里"字数上限"的地方都必须用这里的函数，否则 emoji/组合字符会和 iOS 判断不一致。
 */
fun String.graphemeCount(): Int {
    if (isEmpty()) return 0
    val it = BreakIterator.getCharacterInstance(Locale.ROOT)
    it.setText(this)
    var n = 0
    while (it.next() != BreakIterator.DONE) n++
    return n
}

/** Swift `String(text.prefix(n))`：按字形簇取前 n 个。 */
fun String.prefixGraphemes(n: Int): String {
    if (n <= 0) return ""
    if (length <= n) return this
    val it = BreakIterator.getCharacterInstance(Locale.ROOT)
    it.setText(this)
    var end = 0
    var count = 0
    while (count < n) {
        val next = it.next()
        if (next == BreakIterator.DONE) return this
        end = next
        count++
    }
    return substring(0, end)
}

/** Swift `trimmingCharacters(in: .whitespacesAndNewlines)`。 */
fun String.trimSwift(): String = trim { it.isWhitespace() || Character.isSpaceChar(it) }

/** Swift `unicodeScalars.contains { $0.value == 0 }`。 */
fun String.containsNul(): Boolean = indexOf('\u0000') >= 0

/** Swift `UUID.uuidString`（大写）。排序时用它，与 iOS 的 `($0.dueAt, $0.id.uuidString)` 一致。 */
val java.util.UUID.uuidString: String get() = toString().uppercase(Locale.ROOT)
