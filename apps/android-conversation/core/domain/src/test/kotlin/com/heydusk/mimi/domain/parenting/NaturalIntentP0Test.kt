package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.parenting.ParentingConfirmCard.Block
import com.heydusk.mimi.domain.parenting.ParentingConfirmCard.Tone
import com.heydusk.mimi.domain.parenting.ParentingNaturalProposal.AssumedDay
import com.heydusk.mimi.domain.parenting.ParentingNaturalProposal.AssumedTime
import com.heydusk.mimi.domain.parenting.T.at
import com.heydusk.mimi.domain.parenting.T.calendar
import com.heydusk.mimi.domain.parenting.T.duo
import com.heydusk.mimi.domain.parenting.T.now
import com.heydusk.mimi.domain.parenting.T.tuan
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS #143（c4032e64）意图分类 P0 的移植用例：记录优先、冲突才问、无钟点补 9:00、输入规范化、记一笔|加安排切换。 */
class NaturalIntentP0Test {
    private fun ctx(children: List<ParentingChild> = listOf(tuan)) = ParentingNaturalContext(children, emptyList())
    private fun classify(text: String, c: ParentingNaturalContext = ctx(), clock: Instant = now) = ParentingNaturalInput.classify(text, c, clock, calendar)
    private fun apply(answer: ParentingNaturalAnswer, p: ParentingNaturalProposal, c: ParentingNaturalContext = ctx(), clock: Instant = now) =
        ParentingNaturalInput.applying(answer, p, c, clock, calendar)
    private fun verdict(text: String): String {
        val p = classify(text)
        return when (p.kind) {
            ParentingIntentKind.Observation -> "R"
            ParentingIntentKind.NewArrangement -> "A"
            ParentingIntentKind.Reschedule -> "M"
            ParentingIntentKind.ViewToday, ParentingIntentKind.ViewPlan, ParentingIntentKind.ReviewGrowth -> "Q"
            ParentingIntentKind.Unknown -> if (p.ambiguousIntent) "ASK" else "U"
        }
    }
    private fun card(p: ParentingNaturalProposal, children: List<ParentingChild> = listOf(tuan), clock: Instant = now) =
        ParentingConfirmCard.make(p, T.workspace(children, emptyList()), null, clock)

    @Test fun theRealDeviceSentenceIsARecordAndNeverAsksForAClock() {
        val c = ctx()
        for (text in listOf("今天晚上睡得很早，看了一会儿电视。", "今天晚上睡得很早，看了一会儿电视", "今天晚上睡得很早， 看了一会儿电视！", "今天晚上睡得很早,看了一会儿电视")) {
            val p = classify(text, c)
            assertEquals(ParentingIntentKind.Observation, p.kind, text)
            assertTrue(p.isReadyToConfirm, text)
            assertNull(ParentingNaturalInput.followUp(p, c, calendar), "no 几点开始？ for a record: $text")
            assertEquals(1, ParentingNaturalInput.commands(p, T.workspace(listOf(tuan), emptyList()), ParentingNaturalInput.WriteIDs()).size)
        }
        assertEquals("R", verdict("今天下午宝宝吃了一碗饭"), "the old rule turned this into an arrangement")
    }

    @Test fun aBareTodayIsNoArrangementEvidenceButTodayWithAClockOrNeedStillIs() {
        assertEquals("R", verdict("今天午睡睡了两个小时"))
        assertEquals("A", verdict("团团今天下午5点游泳"))
        assertEquals("A", verdict("今天晚上要给宝宝洗澡"))
        assertEquals("R", verdict("今晚8点吃了药"), "a clock with a past marker is a record")
        assertEquals("R", verdict("宝宝昨天晚上8点就睡了"))
        assertEquals("R", verdict("宝宝8点才醒"))
        assertEquals("A", verdict("提醒我晚上8点半给宝宝喂药"))
        assertEquals("R", verdict("10月20日团团打了疫苗"), "a month-day already past this year is not a future date")
    }

    @Test fun zeroSignalDefaultsToARecordAndConflictingEvidenceAsksOnce() {
        assertEquals("R", verdict("看了一会儿电视"))
        assertEquals("R", verdict("睡得很早"))
        assertEquals("ASK", verdict("晚上给宝宝读绘本"))
        assertEquals("ASK", verdict("今晚读绘本"))
        assertEquals("ASK", verdict("游泳课"))
        val p = classify("今晚读绘本")
        assertEquals(listOf(ParentingMissingField.Intent), p.missing)
        val ask = ParentingNaturalInput.followUp(p, ctx(), calendar)!!
        assertEquals("这条怎么记？", ask.question)
        assertEquals(listOf("已经发生，只记录，不提醒", "还没发生，到点提醒我"), ask.options.map { it.subtitle })
        val record = apply(ParentingNaturalAnswer.Intent(ParentingIntentKind.Observation), p)
        assertEquals(ParentingIntentKind.Observation, record.kind); assertTrue(record.isReadyToConfirm)
        assertEquals("今晚读绘本", record.observationText)
        val plan = apply(ParentingNaturalAnswer.Intent(ParentingIntentKind.NewArrangement), p)
        assertEquals(ParentingIntentKind.NewArrangement, plan.kind)
        assertEquals(at(2026, 10, 25, 19, 0), plan.dueAt, "今晚 without a clock gets the usual evening hour")
        assertEquals(AssumedTime.DayPart, plan.assumedTime)
    }

    @Test fun chitChatAndNonParentingQuestionsAreNotRecordedOrScheduled() {
        for (text in listOf("嗯", "谢谢小咪", "明天会不会下雨", "好的谢谢", "朵朵今天吃饭了吗", "明天天气怎么样", "Swim at five")) {
            val p = classify(text)
            assertEquals(ParentingIntentKind.Unknown, p.kind, text)
            assertFalse(p.ambiguousIntent, text)
        }
        val lead = classify("记一下")
        assertEquals(ParentingIntentKind.Observation, lead.kind)
        assertEquals(listOf(ParentingMissingField.Content), lead.missing, "only a lead: ask what to write")
    }

    @Test fun inputIsNormalisedForFullWidthDigitsAndSpeechSpaces() {
        val p = classify("团团周五下午５点游泳")
        assertEquals(ParentingIntentKind.NewArrangement, p.kind)
        assertEquals(at(2026, 10, 30, 17, 0), p.dueAt)
        assertEquals(at(2026, 10, 30, 17, 0), classify("团团 周五 下午 5 点 游泳").dueAt)
        assertEquals("今天他自己收好书包了", classify("记一下，  今天他自己收好书包了").observationText)
        assertEquals("团团睡了，看电视", NaturalLexicon.normalized("团团　睡了， 看电视"))
        assertEquals("ABC123", NaturalLexicon.normalized("ＡＢＣ１２３"))
    }

    @Test fun switchingTheKindRereadsTheSameWordsAndKeepsTheOtherKindOneTapAway() {
        val w = T.workspace(listOf(tuan), emptyList())
        val record = classify("今天晚上睡得很早，看了一会儿电视")
        assertEquals(ParentingIntentKind.Observation, record.kind)
        val asPlan = apply(ParentingNaturalAnswer.Intent(ParentingIntentKind.NewArrangement), record)
        assertEquals(ParentingIntentKind.NewArrangement, asPlan.kind)
        assertEquals(record.sourceText, asPlan.sourceText)
        assertEquals(at(2026, 10, 25, 19, 0), asPlan.dueAt)
        val planCard = assertNotNull(card(asPlan))
        assertEquals("确认安排", planCard.confirmTitle)
        assertTrue(Block.Note("没说几点，先按你说的时段定了常用时间；不对可以点“手动调整”。", Tone.Unknown) in planCard.blocks)
        val back = apply(ParentingNaturalAnswer.Intent(ParentingIntentKind.Observation), asPlan)
        assertEquals(ParentingIntentKind.Observation, back.kind)
        assertEquals("今天晚上睡得很早，看了一会儿电视", back.observationText)
        val recordCard = assertNotNull(card(back))
        assertEquals("保存观察", recordCard.confirmTitle)
        assertTrue(Block.Note("只记录这一笔，不会提醒你，也不会写入长期记忆；之后可以修改或删除。要提醒就点上面的“加安排”。", Tone.Normal) in recordCard.blocks)
        assertEquals(1, ParentingNaturalInput.commands(back, w, ParentingNaturalInput.WriteIDs()).size)
        assertEquals(2, ParentingNaturalInput.commands(asPlan, w, ParentingNaturalInput.WriteIDs()).size)
    }

    @Test fun switchingToAPlanWithoutADayAsksForTheDayAndCanSwitchBack() {
        val plan = apply(ParentingNaturalAnswer.Intent(ParentingIntentKind.NewArrangement), classify("看了一会儿电视"))
        assertEquals(listOf(ParentingMissingField.Date), plan.missing)
        assertEquals(ParentingMissingField.Date, ParentingNaturalInput.followUp(plan, ctx(), calendar)?.field)
        assertTrue(apply(ParentingNaturalAnswer.Intent(ParentingIntentKind.Observation), plan).isReadyToConfirm)
    }

    /** iOS 方案里的语料（去掉二期才做的 取消 / 兄弟称呼 两行）。 */
    @Test fun theProposalCorpusIsClassifiedAsDecided() {
        val rows = listOf(
            "今晚8点吃了药" to "R", "明晚给团团洗澡" to "A",
            "今天晚上睡得很早，看了一会儿电视" to "R", "今天下午宝宝第一次自己拿勺子吃饭" to "R", "宝宝今天自己扶着沙发走了几步" to "R",
            "团团昨天发烧了，38.5度" to "R", "刚刚团团叫了第一声妈妈" to "R", "今天午睡睡了两个小时" to "R", "今天幼儿园老师夸朵朵了" to "R",
            "晚上吃了一碗面条，还吃了半个苹果" to "R", "朵朵今天一直在哭，不肯去幼儿园" to "R", "今天上午带团团去了公园" to "R",
            "今天学会了自己穿袜子" to "R", "看了一会儿电视" to "R", "睡得很早" to "R", "今天跟奶奶视频了" to "R", "宝宝昨天晚上8点就睡了" to "R",
            "记录：团团今天吃饭很乖" to "R", "明天早上8点带宝宝打疫苗" to "A", "团团周五下午5点游泳" to "A", "记一下周一要带水彩笔" to "A",
            "提醒我晚上8点半给宝宝喂药" to "A", "后天幼儿园家长会" to "A", "下周三团团体检" to "A", "今天晚上要给宝宝洗澡" to "A",
            "明天要带水杯" to "A", "10月30日交费" to "A", "周六上午10点带宝宝去公园" to "A", "明天打算带他去公园" to "A",
            "每周三下午游泳课" to "A", "晚上给宝宝读绘本" to "ASK", "今晚读绘本" to "ASK", "游泳课" to "ASK",
            "团团周五游泳改到下午6点了" to "M", "把钢琴课挪到明天" to "M", "今天有什么安排" to "Q", "看看这周兴趣班怎么排" to "Q",
            "回顾一下团团最近的阅读" to "Q", "嗯" to "U", "谢谢小咪" to "U", "明天会不会下雨" to "U", "记一下" to "R",
        )
        assertEquals(emptyList(), rows.filter { verdict(it.first) != it.second }.map { "${it.second}≠${verdict(it.first)} ${it.first}" })
    }

    @Test fun heldOutSentencesAreClassifiedAsDecided() {
        val rows = listOf(
            "朵朵今天自己穿好了鞋子" to "R", "团团昨晚睡得很香" to "R", "宝宝早上喝了一瓶奶" to "R", "今天下午带朵朵去游乐场玩了很久" to "R",
            "团团今天有点咳嗽" to "R", "今天他在幼儿园和小朋友分享了玩具" to "R", "朵朵会数到二十了" to "R", "宝宝中午没睡觉，一直在玩" to "R",
            "团团今天画了一幅画" to "R", "晚上宝宝哭闹了半个小时" to "R", "今天朵朵吃饭特别香" to "R", "团团刚才叫了爸爸" to "R",
            "宝宝昨天拉肚子" to "R", "今天朵朵第一次游了二十五米" to "R", "上周团团发烧了" to "R", "团团今天看了半小时动画片" to "R",
            "今天下午朵朵跟小朋友吵架了" to "R", "宝宝8点才醒" to "R", "朵朵昨天晚上9点睡的" to "R", "今天他主动整理了玩具" to "R",
            "记一下：团团今天没吃青菜" to "R", "爸爸今天陪团团搭了积木" to "R", "朵朵最近特别爱笑" to "R", "团团今天拉了三次" to "R",
            "下周一团团要带画笔" to "A", "提醒我明天给朵朵交学费" to "A", "周三下午3点朵朵钢琴课" to "A", "明天上午带团团去医院" to "A",
            "后天团团幼儿园春游" to "A", "记得周五给团团带泳衣" to "A", "11月2日朵朵打疫苗" to "A", "明天晚上7点给团团洗澡" to "A",
            "下周二要开家长会" to "A", "今晚8点提醒我给宝宝喂药" to "A", "周日上午团团踢足球" to "A", "别忘了明天带朵朵去体检" to "A",
            "今天下午4点团团英语课" to "A", "预约下周四的儿保" to "A", "周六要去给团团买鞋" to "A", "明早给朵朵带早饭" to "A",
            "读绘本" to "R", "给团团洗澡" to "R", "晚上带朵朵散步" to "ASK", "画画课" to "ASK",
            "朵朵今天吃饭了吗" to "U", "你好" to "U", "好的谢谢" to "U", "明天天气怎么样" to "U", "今天团团有什么安排" to "Q", "下周有什么安排" to "Q",
        )
        assertEquals(emptyList(), rows.filter { verdict(it.first) != it.second }.map { "${it.second}≠${verdict(it.first)} ${it.first}" })
    }

    @Test fun aPartOfTheDayGetsItsUsualHourAndNoPartMeansNine() {
        val cases = listOf(
            "明天早上要带水杯" to at(2026, 10, 26, 8, 0), "明天上午要带水杯" to at(2026, 10, 26, 9, 0),
            "明天中午要带水杯" to at(2026, 10, 26, 12, 0), "明天下午要带水杯" to at(2026, 10, 26, 15, 0),
            "明天晚上要带水杯" to at(2026, 10, 26, 19, 0), "明晚给团团洗澡" to at(2026, 10, 26, 19, 0),
            "明早要带水杯" to at(2026, 10, 26, 8, 0), "明天要带水杯" to at(2026, 10, 26, 9, 0),
        )
        for ((text, due) in cases) {
            val p = classify(text)
            assertEquals(ParentingIntentKind.NewArrangement, p.kind, text)
            assertEquals(due, p.dueAt, text)
            assertTrue(p.isReadyToConfirm, text)
        }
        assertEquals(AssumedTime.NineOClock, classify("明天要带水杯").assumedTime)
        assertEquals(AssumedTime.DayPart, classify("明天下午要带水杯").assumedTime)
        val nine = assertNotNull(card(classify("明天要带水杯")))
        assertTrue(Block.Note("没说几点，先定在 9:00；不对可以点“手动调整”。", Tone.Unknown) in nine.blocks)
    }

    @Test fun todaysUsualHourThatHasPassedMovesToTheNextHalfHourOrTomorrow() {
        var p = classify("今天晚上要给宝宝洗澡", clock = at(2026, 10, 25, 22, 10))
        assertEquals(at(2026, 10, 25, 22, 30), p.dueAt); assertEquals(AssumedTime.LaterThisPart, p.assumedTime)
        p = classify("今天晚上要给宝宝洗澡", clock = at(2026, 10, 25, 22, 40))
        assertEquals(at(2026, 10, 26, 19, 0), p.dueAt); assertEquals(AssumedTime.NextDay, p.assumedTime)
        p = classify("今天上午要带水杯", clock = at(2026, 10, 25, 13, 0))
        assertEquals(at(2026, 10, 26, 9, 0), p.dueAt); assertEquals(AssumedTime.NextDay, p.assumedTime)
        p = classify("今天要带水杯", clock = at(2026, 10, 25, 10, 0))
        assertEquals(at(2026, 10, 26, 9, 0), p.dueAt); assertEquals(AssumedTime.NextDay, p.assumedTime)
        p = classify("今天下午要带水杯", clock = at(2026, 10, 25, 10, 0))
        assertEquals(at(2026, 10, 25, 15, 0), p.dueAt); assertEquals(AssumedTime.DayPart, p.assumedTime)
        p = classify("团团今天下午1点游泳", clock = at(2026, 10, 25, 20, 0))
        assertEquals(at(2026, 10, 25, 13, 0), p.dueAt); assertFalse(p.defaultedTime)
        val late = classify("今天晚上要给宝宝洗澡", clock = at(2026, 10, 25, 22, 10))
        val c = assertNotNull(card(late, clock = at(2026, 10, 25, 22, 10)))
        assertTrue(Block.Note("今天这个时段的常用时间已过，先排在最近的半点；不对可以点“手动调整”。", Tone.Unknown) in c.blocks)
        assertFalse(Block.Note("这个时间已经过去，请确认。", Tone.Warning) in c.blocks)
    }

    @Test fun aClockWithoutHalfOfTheDayStillAsksMorningOrAfternoon() {
        val two = ctx(listOf(tuan, duo))
        val p = classify("团团5点上课", two)
        assertEquals(ParentingIntentKind.NewArrangement, p.kind); assertEquals(listOf(ParentingMissingField.Time), p.missing)
        val ask = ParentingNaturalInput.followUp(p, two, calendar)!!
        assertEquals("是上午还是下午？", ask.question)
        val pm = apply(ask.options[1].answer, p, two)
        assertEquals(at(2026, 10, 25, 17, 0), pm.dueAt); assertEquals(AssumedDay.Today, pm.assumedDay); assertTrue(pm.isReadyToConfirm)
        val clear = classify("团团下午2点上课", two)
        assertEquals(at(2026, 10, 25, 14, 0), clear.dueAt); assertEquals(AssumedDay.Today, clear.assumedDay)
        val passed = classify("团团下午2点上课", two, clock = at(2026, 10, 25, 16, 0))
        assertEquals(at(2026, 10, 26, 14, 0), passed.dueAt); assertEquals(AssumedDay.Tomorrow, passed.assumedDay)
        val stated = classify("团团周五下午2点上课", two, clock = at(2026, 10, 25, 16, 0))
        assertEquals(AssumedDay.None, stated.assumedDay); assertEquals(at(2026, 10, 30, 14, 0), stated.dueAt)
        assertTrue(card(clear, listOf(tuan, duo))!!.blocks.contains(Block.Note("没说日期，先定在今天；不对可以点“手动调整”。", Tone.Unknown)))
    }
}
