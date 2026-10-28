package com.heydusk.mimi.data.account

import com.heydusk.mimi.data.ledger.LedgerScope
import java.util.UUID

/**
 * 与 iOS `DataScope` 对齐的固定分区。本机体验账号与将来的云账号共用同一对 profile/workspace UUID，
 * 隔离靠 subjectID（登录主体），不靠改分区。
 */
object ParentingScopes {
    val USER: LedgerScope = LedgerScope(
        profileID = UUID.fromString("11111111-1111-4111-8111-111111111111"),
        workspaceID = UUID.fromString("11111111-2222-4222-8222-111111111111"),
    )
}
