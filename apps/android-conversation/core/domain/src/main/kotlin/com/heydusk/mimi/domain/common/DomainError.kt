package com.heydusk.mimi.domain.common

/**
 * 与 iOS `catError`（StoreTypes.swift）一一对应。消息就是给家长看的文案（同一批中文原句，界面按 UIStrings.json 取词）。
 */
sealed class DomainError(override val message: String) : Exception(message) {
    data object Missing : DomainError("这项内容不存在或已删除。")

    data object Conflict : DomainError("内容已更新，请重新打开后再保存。未保存输入仍保留。")

    data object ScopeMismatch : DomainError("无法访问其他数据分区的内容。")

    data class Invalid(val text: String) : DomainError(text)
    data class Unavailable(val text: String) : DomainError(text)
    data class Corrupt(val text: String) : DomainError(text)
}
