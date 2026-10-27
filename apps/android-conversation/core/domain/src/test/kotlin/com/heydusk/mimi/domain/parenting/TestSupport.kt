package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.MimiCalendar
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** 测试公用：固定的 Asia/Shanghai 日历、`at()` 构造时间、两个孩子和一个"游泳课"安排。（对应 iOS 测试里每个文件开头的同一批辅助。） */
internal object T {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    val calendar = MimiCalendar(zone)

    fun at(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId = T.zone): Instant =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-" + n.toString(16).uppercase().padStart(12, '0'))

    /** 周日 2026-10-25 10:00（Asia/Shanghai）。 */
    val now: Instant = at(2026, 10, 25, 10, 0)

    val tuan = ParentingChild(UUID.fromString("00000000-0000-0000-0000-0000000000A1"), "团团", Instant.ofEpochSecond(1_700_000_000))
    val duo = ParentingChild(UUID.fromString("00000000-0000-0000-0000-0000000000A2"), "朵朵", Instant.ofEpochSecond(1_700_000_001))

    fun swim(
        due: Instant, state: ParentingArrangementState = ParentingArrangementState.Planned, child: ParentingChild = tuan,
        title: String = "游泳课", id: UUID = UUID.randomUUID(),
    ) = ParentingArrangement(
        id, child.id, title, due, null, state, "手动", Instant.ofEpochSecond(1_700_000_100), Instant.ofEpochSecond(1_700_000_100),
    )

    fun workspace(
        children: List<ParentingChild>, arrangements: List<ParentingArrangement> = emptyList(), revision: Int = 1,
        records: List<ParentingRecord> = emptyList(), goals: List<ParentingGoal> = emptyList(),
    ) = ParentingWorkspace(revision = revision, children = children, goals = goals, arrangements = arrangements, records = records)

    fun record(kind: ParentingRecordKind, whenAt: Instant, text: String, child: ParentingChild = tuan, goalID: UUID? = null) =
        ParentingRecord(UUID.randomUUID(), child.id, goalID, null, kind, text, text, whenAt, whenAt)
}
