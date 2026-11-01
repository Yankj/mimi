package com.heydusk.mimi.feature.parenting.chrome

/**
 * 底栏一行双态的状态机（INTERACTION.md §2 / iOS `ParentingChromeMode`）。
 * 只有 input、pickTab、detail。过渡锁期间忽略叶标、tab 和收起。
 * 时长是预算：界面按这些毫秒上锁，matchedGeometry 留给 iOS DeviceTrial 之后。
 */
enum class ParentingChromeMode {
    Input,
    PickTab,
    Detail,
}

enum class ChromeStep {
    Idle,
    /** input→pickTab：先失焦，再切到 pickTab。 */
    ResignThenPickTab,
    /** 切态之后的锁，结束前不接下一手势。 */
    Hold,
}

object ParentingChromeMotion {
    const val RESIGN_MS = 50L
    const val INPUT_TO_PICK_MORPH_MS = 250L
    const val INPUT_TO_PICK_TOTAL_MS = RESIGN_MS + INPUT_TO_PICK_MORPH_MS
    const val PICK_TO_INPUT_MS = 280L
    const val DETAIL_PRESENT_MS = 280L
    const val DETAIL_DISMISS_MS = 240L
    const val REDUCE_MOTION_MS = 120L
    /** 我们家：短 crossfade，落在规范 120–200ms 里，与 iOS 落地的 160ms 相同。 */
    const val FAMILY_CROSSFADE_MS = 160L
    const val SPRING_RESPONSE = 0.32f
    const val SPRING_DAMPING = 0.86f
}

object ParentingChromeTags {
    const val TYPE_CHIP = "parenting.chrome.typeChip"
    const val DISMISS_PICK_TAB = "parenting.chrome.dismissPickTab"
}

data class ParentingChromeState<T>(
    val mode: ParentingChromeMode = ParentingChromeMode.Input,
    val animating: Boolean = false,
    val naturalPresented: Boolean = false,
    val tab: T,
    val resignFocusToken: Int = 0,
    val transitionId: Int = 0,
    val reduceMotion: Boolean = false,
    val step: ChromeStep = ChromeStep.Idle,
    val stepMs: Long = 0,
    /** 点了 tab（含再点当前项）就加一，壳据此回到该 tab 的根页面。 */
    val rootEpoch: Int = 0,
) {
    /** detail 仍是输入行外观；pickTab 才换成三 tab。 */
    val showComposerSlot: Boolean
        get() = mode != ParentingChromeMode.PickTab || naturalPresented

    val showPickTabSlot: Boolean
        get() = mode == ParentingChromeMode.PickTab && !naturalPresented

    fun typeChipVisible(showsComposer: Boolean): Boolean =
        showsComposer && mode == ParentingChromeMode.Input && !naturalPresented

    fun tapTypeChip(showsComposer: Boolean): ParentingChromeState<T> {
        if (animating || mode != ParentingChromeMode.Input || naturalPresented || !showsComposer) return this
        val id = transitionId + 1
        val token = resignFocusToken + 1
        return if (reduceMotion) {
            copy(
                animating = true,
                transitionId = id,
                resignFocusToken = token,
                mode = ParentingChromeMode.PickTab,
                step = ChromeStep.Hold,
                stepMs = ParentingChromeMotion.REDUCE_MOTION_MS,
            )
        } else {
            copy(
                animating = true,
                transitionId = id,
                resignFocusToken = token,
                step = ChromeStep.ResignThenPickTab,
                stepMs = ParentingChromeMotion.RESIGN_MS,
            )
        }
    }

    fun dismissPickTab(): ParentingChromeState<T> {
        if (animating || mode != ParentingChromeMode.PickTab) return this
        return copy(
            animating = true,
            transitionId = transitionId + 1,
            mode = ParentingChromeMode.Input,
            step = ChromeStep.Hold,
            stepMs = if (reduceMotion) ParentingChromeMotion.REDUCE_MOTION_MS else ParentingChromeMotion.PICK_TO_INPUT_MS,
        )
    }

    /**
     * @param itemShowsComposer false = 我们家：直接一排 tab，不做输入行 morph（H7）。
     */
    fun selectFromPick(item: T, itemShowsComposer: Boolean): ParentingChromeState<T> {
        if (animating || mode != ParentingChromeMode.PickTab) return this
        val rooted = copy(rootEpoch = rootEpoch + 1)
        if (!itemShowsComposer) {
            return rooted.copy(
                animating = true,
                transitionId = transitionId + 1,
                tab = item,
                mode = ParentingChromeMode.Input,
                step = ChromeStep.Hold,
                stepMs = if (reduceMotion) ParentingChromeMotion.REDUCE_MOTION_MS else ParentingChromeMotion.FAMILY_CROSSFADE_MS,
            )
        }
        return rooted.copy(tab = item).dismissPickTab()
    }

    /** 没有输入卡时的常驻三 tab。再点当前项也记一次，用来回到根页面。动画锁期间忽略。 */
    fun switchSteadyTab(item: T): ParentingChromeState<T> {
        if (animating) return this
        val rooted = copy(rootEpoch = rootEpoch + 1)
        if (item == tab && mode == ParentingChromeMode.Input && !naturalPresented) return rooted
        return rooted.copy(tab = item, mode = ParentingChromeMode.Input, naturalPresented = false)
    }

    /** 通知等外部跳转：离开 pickTab，不主动关掉已经打开的 detail。 */
    fun reveal(item: T): ParentingChromeState<T> {
        val base = if (animating || step != ChromeStep.Idle) cancel() else this
        return if (base.mode == ParentingChromeMode.PickTab) {
            base.copy(tab = item, mode = ParentingChromeMode.Input)
        } else {
            base.copy(tab = item)
        }
    }

    fun enterDetail(): ParentingChromeState<T> = cancel().copy(
        mode = ParentingChromeMode.Detail,
        naturalPresented = true,
    )

    fun dismissDetail(): ParentingChromeState<T> {
        if (!naturalPresented && mode != ParentingChromeMode.Detail) return this
        return cancel().copy(mode = ParentingChromeMode.Input, naturalPresented = false)
    }

    /** 聚焦输入：先取消进行中的过渡，再离开 detail / pickTab（H3 / H6）。 */
    fun onComposerFocused(): ParentingChromeState<T> {
        val cleared = if (animating || step != ChromeStep.Idle) cancel() else this
        return when {
            cleared.naturalPresented || cleared.mode == ParentingChromeMode.Detail ->
                cleared.copy(mode = ParentingChromeMode.Input, naturalPresented = false)
            cleared.mode == ParentingChromeMode.PickTab ->
                cleared.copy(mode = ParentingChromeMode.Input)
            cleared.mode != ParentingChromeMode.Input ->
                cleared.copy(mode = ParentingChromeMode.Input)
            else -> cleared
        }
    }

    fun cancel(): ParentingChromeState<T> = copy(
        animating = false,
        step = ChromeStep.Idle,
        stepMs = 0,
        transitionId = transitionId + 1,
    )

    fun advance(ms: Long): ParentingChromeState<T> {
        if (ms <= 0L || !animating || step == ChromeStep.Idle) return this
        if (ms < stepMs) return copy(stepMs = stepMs - ms)
        val leftover = ms - stepMs
        val next = completeStep()
        return if (leftover > 0L && next.animating) next.advance(leftover) else next
    }

    private fun completeStep(): ParentingChromeState<T> = when (step) {
        ChromeStep.ResignThenPickTab -> copy(
            mode = ParentingChromeMode.PickTab,
            step = ChromeStep.Hold,
            stepMs = if (reduceMotion) ParentingChromeMotion.REDUCE_MOTION_MS else ParentingChromeMotion.INPUT_TO_PICK_MORPH_MS,
        )
        ChromeStep.Hold -> copy(animating = false, step = ChromeStep.Idle, stepMs = 0)
        ChromeStep.Idle -> this
    }
}
