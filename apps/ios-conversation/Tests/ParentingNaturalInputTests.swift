import Foundation
import XCTest
@testable import cat

/// Pure, synthetic tests: no model, network, notification centre or simulator state. They prove the local rules only.
final class ParentingNaturalInputTests: XCTestCase {
    private var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "Asia/Shanghai")!; return c
    }()
    /// Sunday 2026-10-25 10:00 (Asia/Shanghai).
    private var now: Date { at(2026, 10, 25, 10, 0) }
    private func at(_ y: Int, _ m: Int, _ d: Int, _ h: Int, _ min: Int) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))!
    }
    private let tuan = ParentingChild(id: UUID(uuidString: "00000000-0000-0000-0000-0000000000A1")!, name: "团团", createdAt: Date(timeIntervalSince1970: 1_700_000_000))
    private let duo = ParentingChild(id: UUID(uuidString: "00000000-0000-0000-0000-0000000000A2")!, name: "朵朵", createdAt: Date(timeIntervalSince1970: 1_700_000_001))

    private func swim(due: Date, state: ParentingArrangementState = .planned, child: ParentingChild? = nil) -> ParentingArrangement {
        let c = child ?? tuan
        return ParentingArrangement(id: UUID(), childID: c.id, title: "游泳课", dueAt: due, goalID: nil, state: state,
                                    sourceText: "手动", createdAt: Date(timeIntervalSince1970: 1_700_000_100), updatedAt: Date(timeIntervalSince1970: 1_700_000_100))
    }
    private func context(children: [ParentingChild], arrangements: [ParentingArrangement] = []) -> ParentingNaturalContext {
        .init(children: children, arrangements: arrangements)
    }
    private func classify(_ text: String, _ ctx: ParentingNaturalContext) -> ParentingNaturalProposal {
        ParentingNaturalInput.classify(text, context: ctx, now: now, calendar: calendar)
    }
    private func workspace(children: [ParentingChild], arrangements: [ParentingArrangement] = []) -> ParentingWorkspace {
        ParentingWorkspace(revision: 1, children: children, arrangements: arrangements)
    }

    // MARK: Intent classification
    func testNewArrangementIsClassifiedAsCaptureWithExtractedFields() {
        let p = classify("团团周五下午5点游泳", context(children: [tuan, duo]))
        XCTAssertEqual(p.kind, .newArrangement)
        XCTAssertEqual(p.mode, .capture)
        XCTAssertEqual(p.childID, tuan.id)
        XCTAssertEqual(p.title, "游泳")
        XCTAssertEqual(p.dueAt, at(2026, 10, 30, 17, 0))
        XCTAssertTrue(p.missing.isEmpty)
        XCTAssertTrue(p.isReadyToConfirm)
    }

    func testObservationIsClassifiedAndKeepsTheParentsWords() {
        let p = classify("记一下，今天他自己收好书包了", context(children: [tuan]))
        XCTAssertEqual(p.kind, .observation)
        XCTAssertEqual(p.mode, .capture)
        XCTAssertEqual(p.childID, tuan.id, "one child: bound without asking")
        XCTAssertEqual(p.observationText, "今天他自己收好书包了")
        XCTAssertTrue(p.missing.isEmpty)
    }

    func testFuturePlansWithADateAreNotMistakenForObservations() {
        let p = classify("记一下周一要带水彩笔", context(children: [tuan]))
        XCTAssertEqual(p.kind, .newArrangement)
        XCTAssertTrue(p.missing.isEmpty, "a date without a clock is filled with 9:00, not asked")
        XCTAssertEqual(p.dueAt, at(2026, 10, 26, 9, 0))
        XCTAssertTrue(p.defaultedTime)
    }

    func testQueriesAreClassifiedAsPlanAndReviewAndNeverWrite() {
        let ws = workspace(children: [tuan])
        let ctx = context(children: [tuan])
        let today = classify("今天团团有什么安排", ctx)
        XCTAssertEqual(today.kind, .viewToday); XCTAssertEqual(today.mode, .plan)
        let plan = classify("看看兴趣班怎么排", ctx)
        XCTAssertEqual(plan.kind, .viewPlan); XCTAssertEqual(plan.mode, .plan)
        let review = classify("回顾一下团团最近的阅读", ctx)
        XCTAssertEqual(review.kind, .reviewGrowth); XCTAssertEqual(review.mode, .review)
        for p in [today, plan, review] {
            XCTAssertFalse(p.kind.writes)
            XCTAssertTrue(p.missing.isEmpty)
            XCTAssertTrue(ParentingNaturalInput.commands(for: p, workspace: ws, ids: .init()).isEmpty)
            let card = ParentingConfirmCard.make(p, workspace: ws, remindersEnabled: nil, now: now)
            XCTAssertEqual(card?.writes, false)
        }
    }

    func testRescheduleBindsTheExistingArrangementAndInheritsItsDay() {
        let existing = swim(due: at(2026, 10, 30, 17, 0))
        let ctx = context(children: [tuan], arrangements: [existing])
        let p = classify("团团周五游泳改到下午6点了", ctx)
        XCTAssertEqual(p.kind, .reschedule)
        XCTAssertEqual(p.targetID, existing.id)
        XCTAssertEqual(p.dueAt, at(2026, 10, 30, 18, 0))
        XCTAssertTrue(p.inheritedDate)
        XCTAssertTrue(p.missing.isEmpty)
    }

    func testIntentDetectionIsStableWithoutAnyChild() {
        XCTAssertEqual(classify("嗯", context(children: [])).kind, .unknown)
    }


    // MARK: Intent classification (P0): record vs arrangement
    private func verdict(_ text: String) -> String {
        let p = classify(text, context(children: [tuan]))
        switch p.kind {
        case .observation: return "R"
        case .newArrangement: return "A"
        case .reschedule: return "M"
        case .cancel: return "C"
        case .viewToday, .viewPlan, .reviewGrowth: return "Q"
        case .profileFact: return "F"
        case .unknown: return p.ambiguousIntent ? "ASK" : "U"
        }
    }

    func testTheRealDeviceSentenceIsARecordAndNeverAsksForAClock() {
        let ctx = context(children: [tuan])
        for text in ["今天晚上睡得很早，看了一会儿电视。", "今天晚上睡得很早，看了一会儿电视", "今天晚上睡得很早， 看了一会儿电视！", "今天晚上睡得很早,看了一会儿电视"] {
            let p = classify(text, ctx)
            XCTAssertEqual(p.kind, .observation, text)
            XCTAssertTrue(p.isReadyToConfirm, text)
            XCTAssertNil(ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar), "no 几点开始？ for a record: " + text)
            XCTAssertTrue(ParentingNaturalInput.commands(for: p, workspace: workspace(children: [tuan]), ids: .init()).count == 1)
        }
        XCTAssertEqual(verdict("今天下午宝宝吃了一碗饭"), "R", "the old rule turned this into an arrangement")
    }

    func testABareTodayIsNoArrangementEvidenceButTodayWithAClockOrNeedStillIs() {
        XCTAssertEqual(verdict("今天午睡睡了两个小时"), "R")
        XCTAssertEqual(verdict("团团今天下午5点游泳"), "A")
        XCTAssertEqual(verdict("今天晚上要给宝宝洗澡"), "A")
        XCTAssertEqual(verdict("今晚8点吃了药"), "R", "a clock with a past marker is a record")
        XCTAssertEqual(verdict("宝宝昨天晚上8点就睡了"), "R")
        XCTAssertEqual(verdict("宝宝8点才醒"), "R")
        XCTAssertEqual(verdict("提醒我晚上8点半给宝宝喂药"), "A")
        XCTAssertEqual(verdict("10月20日团团打了疫苗"), "R", "a month-day already past this year is not a future date")
    }

    func testZeroSignalDefaultsToARecordAndConflictingEvidenceAsksOnce() {
        XCTAssertEqual(verdict("看了一会儿电视"), "R")
        XCTAssertEqual(verdict("睡得很早"), "R")
        XCTAssertEqual(verdict("晚上给宝宝读绘本"), "ASK")
        XCTAssertEqual(verdict("今晚读绘本"), "ASK")
        XCTAssertEqual(verdict("游泳课"), "ASK")
        let ctx = context(children: [tuan])
        let p = classify("今晚读绘本", ctx)
        XCTAssertEqual(p.missing, [.intent])
        let record = ParentingNaturalInput.applying(.intent(.observation), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(record.kind, .observation); XCTAssertTrue(record.isReadyToConfirm)
        XCTAssertEqual(record.observationText, "今晚读绘本")
        let plan = ParentingNaturalInput.applying(.intent(.newArrangement), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(plan.kind, .newArrangement)
        XCTAssertEqual(plan.dueAt, at(2026, 10, 25, 19, 0), "今晚 without a clock gets the usual evening hour, 19:00")
        XCTAssertEqual(plan.assumedTime, .dayPart)
    }

    func testChitChatAndNonParentingQuestionsAreNotRecordedOrScheduled() {
        for text in ["嗯", "谢谢小咪", "明天会不会下雨", "好的谢谢", "朵朵今天吃饭了吗", "明天天气怎么样", "Swim at five"] {
            let p = classify(text, context(children: [tuan]))
            XCTAssertEqual(p.kind, .unknown, text)
            XCTAssertFalse(p.ambiguousIntent, text)
        }
        let lead = classify("记一下", context(children: [tuan]))
        XCTAssertEqual(lead.kind, .observation)
        XCTAssertEqual(lead.missing, [.content], "only a lead: ask what to write")
    }

    func testInputIsNormalisedForFullWidthDigitsAndSpeechSpaces() {
        let ctx = context(children: [tuan])
        let p = classify("团团周五下午５点游泳", ctx)
        XCTAssertEqual(p.kind, .newArrangement)
        XCTAssertEqual(p.dueAt, at(2026, 10, 30, 17, 0))
        let spoken = classify("团团 周五 下午 5 点 游泳", ctx)
        XCTAssertEqual(spoken.dueAt, at(2026, 10, 30, 17, 0))
        let q = classify("记一下，  今天他自己收好书包了", ctx)
        XCTAssertEqual(q.observationText, "今天他自己收好书包了")
        XCTAssertEqual(ParentingNaturalLexicon.normalized("团团　睡了， 看电视"), "团团睡了，看电视")
    }

    func testSwitchingTheKindRereadsTheSameWordsAndKeepsTheOtherKindOneTapAway() throws {
        let ctx = context(children: [tuan])
        let ws = workspace(children: [tuan])
        let record = classify("今天晚上睡得很早，看了一会儿电视", ctx)
        XCTAssertEqual(record.kind, .observation)
        let asPlan = ParentingNaturalInput.applying(.intent(.newArrangement), to: record, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(asPlan.kind, .newArrangement)
        XCTAssertEqual(asPlan.sourceText, record.sourceText)
        XCTAssertEqual(asPlan.dueAt, at(2026, 10, 25, 19, 0))
        let planCard = try XCTUnwrap(ParentingConfirmCard.make(asPlan, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertEqual(planCard.confirmTitle, "确认安排")
        XCTAssertTrue(planCard.blocks.contains(.note("没说几点，先按你说的时段定了常用时间；不对可以点“手动调整”。", .unknown)))
        let back = ParentingNaturalInput.applying(.intent(.observation), to: asPlan, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(back.kind, .observation)
        XCTAssertEqual(back.observationText, "今天晚上睡得很早，看了一会儿电视")
        let recordCard = try XCTUnwrap(ParentingConfirmCard.make(back, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertEqual(recordCard.confirmTitle, "保存观察")
        XCTAssertTrue(recordCard.blocks.contains(.note("只记录这一笔，不会提醒你，也不会写入长期记忆；之后可以修改或删除。要提醒就点上面的“加安排”。", .normal)))
        XCTAssertEqual(ParentingNaturalInput.commands(for: back, workspace: ws, ids: .init()).count, 1)
        XCTAssertEqual(ParentingNaturalInput.commands(for: asPlan, workspace: ws, ids: .init()).count, 2)
    }

    func testSwitchingToAPlanWithoutADayAsksForTheDayAndCanSwitchBack() {
        let ctx = context(children: [tuan])
        let record = classify("看了一会儿电视", ctx)
        let plan = ParentingNaturalInput.applying(.intent(.newArrangement), to: record, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(plan.missing, [.date])
        XCTAssertEqual(ParentingNaturalInput.followUp(for: plan, context: ctx, calendar: calendar)?.field, .date)
        let back = ParentingNaturalInput.applying(.intent(.observation), to: plan, context: ctx, now: now, calendar: calendar)
        XCTAssertTrue(back.isReadyToConfirm)
    }

    @MainActor func testTheTypeSwitchIsOnlyOfferedForRecordAndPlanBeforeAnythingIsSaved() {
        let ctx = context(children: [tuan])
        let input = ParentingNaturalInputModel()
        XCTAssertFalse(input.canSwitchType)
        input.draft = "今天晚上睡得很早，看了一会儿电视"
        input.submit(context: ctx, now: now)
        XCTAssertEqual(input.proposal?.kind, .observation)
        XCTAssertEqual(input.phase, .confirming)
        XCTAssertTrue(input.canSwitchType)
        input.switchType(to: .newArrangement, context: ctx, now: now)
        XCTAssertEqual(input.proposal?.kind, .newArrangement)
        input.switchType(to: .observation, context: ctx, now: now)
        XCTAssertEqual(input.proposal?.kind, .observation)
        input.draft = "团团周五游泳改到六点"
        input.submit(context: ctx, now: now)
        XCTAssertFalse(input.canSwitchType, "a reschedule has no 记一笔 | 加安排 switch")
    }

    /// The 48-sentence corpus from the proposal (records, arrangements, ask, reschedule, query, chit-chat). The second-phase kinds
    /// (完成, 取消, 目标, 一句两件事) are deliberately not asserted: they are not in the first version.
    func testTheProposalCorpusIsClassifiedAsDecided() {
        let rows: [(String, String)] = [
        ("取消周五的游泳", "C"), ("今晚8点吃了药", "R"), ("明晚给团团洗澡", "A"), ("老大2点上课", "A"),
        ("今天晚上睡得很早，看了一会儿电视", "R"),
        ("今天下午宝宝第一次自己拿勺子吃饭", "R"),
        ("宝宝今天自己扶着沙发走了几步", "R"),
        ("团团昨天发烧了，38.5度", "R"),
        ("刚刚团团叫了第一声妈妈", "R"),
        ("今天午睡睡了两个小时", "R"),
        ("今天幼儿园老师夸朵朵了", "R"),
        ("晚上吃了一碗面条，还吃了半个苹果", "R"),
        ("朵朵今天一直在哭，不肯去幼儿园", "R"),
        ("今天上午带团团去了公园", "R"),
        ("今天学会了自己穿袜子", "R"),
        ("看了一会儿电视", "R"),
        ("睡得很早", "R"),
        ("今天跟奶奶视频了", "R"),
        ("宝宝昨天晚上8点就睡了", "R"),
        ("今晚8点吃了药", "R"),
        ("记录：团团今天吃饭很乖", "R"),
        ("明天早上8点带宝宝打疫苗", "A"),
        ("团团周五下午5点游泳", "A"),
        ("记一下周一要带水彩笔", "A"),
        ("提醒我晚上8点半给宝宝喂药", "A"),
        ("后天幼儿园家长会", "A"),
        ("下周三团团体检", "A"),
        ("今天晚上要给宝宝洗澡", "A"),
        ("明天要带水杯", "A"),
        ("10月30日交费", "A"),
        ("周六上午10点带宝宝去公园", "A"),
        ("明天打算带他去公园", "A"),
        ("每周三下午游泳课", "A"),
        ("晚上给宝宝读绘本", "ASK"),
        ("今晚读绘本", "ASK"),
        ("游泳课", "ASK"),
        ("团团周五游泳改到下午6点了", "M"),
        ("把钢琴课挪到明天", "M"),
        ("今天有什么安排", "Q"),
        ("看看这周兴趣班怎么排", "Q"),
        ("回顾一下团团最近的阅读", "Q"),
        ("嗯", "U"),
        ("谢谢小咪", "U"),
        ("明天会不会下雨", "U"),
        ("记一下", "R"),
        ]
        var wrong: [String] = []
        for (text, expected) in rows where verdict(text) != expected { wrong.append("\(expected)≠\(verdict(text)) \(text)") }
        XCTAssertEqual(wrong, [])
    }

    /// Sentences written after the rules, not used to tune them first: records, plans, weak and chatty ones.
    func testHeldOutSentencesAreClassifiedAsDecided() {
        let rows: [(String, String)] = [
        ("朵朵今天自己穿好了鞋子", "R"),
        ("团团昨晚睡得很香", "R"),
        ("宝宝早上喝了一瓶奶", "R"),
        ("今天下午带朵朵去游乐场玩了很久", "R"),
        ("团团今天有点咳嗽", "R"),
        ("今天他在幼儿园和小朋友分享了玩具", "R"),
        ("朵朵会数到二十了", "R"),
        ("宝宝中午没睡觉，一直在玩", "R"),
        ("团团今天画了一幅画", "R"),
        ("晚上宝宝哭闹了半个小时", "R"),
        ("今天朵朵吃饭特别香", "R"),
        ("团团刚才叫了爸爸", "R"),
        ("宝宝昨天拉肚子", "R"),
        ("今天朵朵第一次游了二十五米", "R"),
        ("上周团团发烧了", "R"),
        ("团团今天看了半小时动画片", "R"),
        ("今天下午朵朵跟小朋友吵架了", "R"),
        ("宝宝8点才醒", "R"),
        ("朵朵昨天晚上9点睡的", "R"),
        ("今天他主动整理了玩具", "R"),
        ("记一下：团团今天没吃青菜", "R"),
        ("爸爸今天陪团团搭了积木", "R"),
        ("朵朵最近特别爱笑", "R"),
        ("团团今天拉了三次", "R"),
        ("下周一团团要带画笔", "A"),
        ("提醒我明天给朵朵交学费", "A"),
        ("周三下午3点朵朵钢琴课", "A"),
        ("明天上午带团团去医院", "A"),
        ("后天团团幼儿园春游", "A"),
        ("记得周五给团团带泳衣", "A"),
        ("11月2日朵朵打疫苗", "A"),
        ("明天晚上7点给团团洗澡", "A"),
        ("下周二要开家长会", "A"),
        ("今晚8点提醒我给宝宝喂药", "A"),
        ("周日上午团团踢足球", "A"),
        ("别忘了明天带朵朵去体检", "A"),
        ("今天下午4点团团英语课", "A"),
        ("预约下周四的儿保", "A"),
        ("周六要去给团团买鞋", "A"),
        ("明早给朵朵带早饭", "A"),
        ("读绘本", "R"),
        ("给团团洗澡", "R"),
        ("晚上带朵朵散步", "ASK"),
        ("画画课", "ASK"),
        ("朵朵今天吃饭了吗", "U"),
        ("你好", "U"),
        ("好的谢谢", "U"),
        ("明天天气怎么样", "U"),
        ("今天团团有什么安排", "Q"),
        ("下周有什么安排", "Q"),
        ]
        var wrong: [String] = []
        for (text, expected) in rows where verdict(text) != expected { wrong.append("\(expected)≠\(verdict(text)) \(text)") }
        XCTAssertEqual(wrong, [])
    }



    // MARK: Time defaults: part of the day, today already past, a clock without a date

    private func classifyAt(_ text: String, _ ctx: ParentingNaturalContext, _ clock: Date) -> ParentingNaturalProposal {
        ParentingNaturalInput.classify(text, context: ctx, now: clock, calendar: calendar)
    }

    func testAPartOfTheDayGetsItsUsualHour() {
        let ctx = context(children: [tuan])
        let cases: [(String, Date)] = [
            ("明天早上要带水杯", at(2026, 10, 26, 8, 0)), ("明天上午要带水杯", at(2026, 10, 26, 9, 0)),
            ("明天中午要带水杯", at(2026, 10, 26, 12, 0)), ("明天下午要带水杯", at(2026, 10, 26, 15, 0)),
            ("明天晚上要带水杯", at(2026, 10, 26, 19, 0)), ("明晚给团团洗澡", at(2026, 10, 26, 19, 0)),
            ("明早要带水杯", at(2026, 10, 26, 8, 0)), ("明天要带水杯", at(2026, 10, 26, 9, 0)),
        ]
        for (text, due) in cases {
            let p = classify(text, ctx)
            XCTAssertEqual(p.kind, .newArrangement, text)
            XCTAssertEqual(p.dueAt, due, text)
            XCTAssertTrue(p.isReadyToConfirm, text)
        }
        XCTAssertEqual(classify("明天要带水杯", ctx).assumedTime, .nineOClock)
        XCTAssertEqual(classify("明天下午要带水杯", ctx).assumedTime, .dayPart)
    }

    func testTodaysUsualHourThatHasPassedMovesToTheNextHalfHourOrTomorrow() {
        let ctx = context(children: [tuan])
        // 22:10, "今天晚上" (usual 19:00 has passed, the evening lasts until 23:00): the next half hour.
        var p = classifyAt("今天晚上要给宝宝洗澡", ctx, at(2026, 10, 25, 22, 10))
        XCTAssertEqual(p.dueAt, at(2026, 10, 25, 22, 30)); XCTAssertEqual(p.assumedTime, .laterThisPart)
        // 22:40: the next half hour (23:00) is outside the evening, so tomorrow's usual hour.
        p = classifyAt("今天晚上要给宝宝洗澡", ctx, at(2026, 10, 25, 22, 40))
        XCTAssertEqual(p.dueAt, at(2026, 10, 26, 19, 0)); XCTAssertEqual(p.assumedTime, .nextDay)
        // 13:00, "今天上午": over, so tomorrow 9:00.
        p = classifyAt("今天上午要带水杯", ctx, at(2026, 10, 25, 13, 0))
        XCTAssertEqual(p.dueAt, at(2026, 10, 26, 9, 0)); XCTAssertEqual(p.assumedTime, .nextDay)
        // 10:00, "今天要带水杯" (no part, 9:00 passed): tomorrow 9:00.
        p = classifyAt("今天要带水杯", ctx, at(2026, 10, 25, 10, 0))
        XCTAssertEqual(p.dueAt, at(2026, 10, 26, 9, 0)); XCTAssertEqual(p.assumedTime, .nextDay)
        // Still ahead today: kept.
        p = classifyAt("今天下午要带水杯", ctx, at(2026, 10, 25, 10, 0))
        XCTAssertEqual(p.dueAt, at(2026, 10, 25, 15, 0)); XCTAssertEqual(p.assumedTime, .dayPart)
        // An explicit clock is never moved (the card warns that it is in the past).
        p = classifyAt("团团今天下午1点游泳", ctx, at(2026, 10, 25, 20, 0))
        XCTAssertEqual(p.dueAt, at(2026, 10, 25, 13, 0)); XCTAssertFalse(p.defaultedTime)
    }

    func testTheCardSaysWhatWasAssumed() throws {
        let ctx = context(children: [tuan]); let ws = workspace(children: [tuan])
        let late = classifyAt("今天晚上要给宝宝洗澡", ctx, at(2026, 10, 25, 22, 10))
        let card = try XCTUnwrap(ParentingConfirmCard.make(late, workspace: ws, remindersEnabled: nil, now: at(2026, 10, 25, 22, 10)))
        XCTAssertTrue(card.blocks.contains(.note("今天这个时段的常用时间已过，先排在最近的半点；不对可以点“手动调整”。", .unknown)))
        XCTAssertFalse(card.blocks.contains(.note("这个时间已经过去，请确认。", .warning)))
        let next = classifyAt("今天要带水杯", ctx, at(2026, 10, 25, 10, 0))
        let card2 = try XCTUnwrap(ParentingConfirmCard.make(next, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertTrue(card2.blocks.contains(.note("今天这个时间已过，先排在明天；要改回今天，点“手动调整”。", .unknown)))
    }

    func testAClockWithoutADateMeansTodayOrTomorrowAndIsNotAskedWhichDay() {
        let two = context(children: [tuan, duo])
        // "2点" has no half of the day: ask 上午 / 下午 only, never the date.
        let p = classifyAt("老大2点上课", two, at(2026, 10, 25, 10, 0))
        XCTAssertEqual(p.kind, .newArrangement); XCTAssertEqual(p.childID, tuan.id); XCTAssertEqual(p.title, "上课")
        XCTAssertEqual(p.missing, [.time])
        let ask = ParentingNaturalInput.followUp(for: p, context: two, calendar: calendar)
        XCTAssertEqual(ask?.field, .time)
        XCTAssertEqual(ask?.options.map(\.label), [.dateTime(at(2026, 10, 25, 2, 0)), .dateTime(at(2026, 10, 25, 14, 0))])
        let pm = ParentingNaturalInput.applying(ask!.options[1].answer, to: p, context: two, now: at(2026, 10, 25, 10, 0), calendar: calendar)
        XCTAssertEqual(pm.dueAt, at(2026, 10, 25, 14, 0)); XCTAssertEqual(pm.assumedDay, .today); XCTAssertTrue(pm.isReadyToConfirm)
        // At 15:00 the 14:00 of today has passed: tomorrow.
        let late = classifyAt("老大2点上课", two, at(2026, 10, 25, 15, 0))
        let lateDone = ParentingNaturalInput.applying(.dueAt(at(2026, 10, 25, 14, 0)), to: late, context: two, now: at(2026, 10, 25, 15, 0), calendar: calendar)
        XCTAssertEqual(lateDone.dueAt, at(2026, 10, 26, 14, 0)); XCTAssertEqual(lateDone.assumedDay, .tomorrow)
        // A clear clock: straight to the card, today if still ahead, otherwise tomorrow.
        let clear = classifyAt("老大下午2点上课", two, at(2026, 10, 25, 10, 0))
        XCTAssertEqual(clear.dueAt, at(2026, 10, 25, 14, 0)); XCTAssertEqual(clear.assumedDay, .today); XCTAssertTrue(clear.isReadyToConfirm)
        let passed = classifyAt("老大下午2点上课", two, at(2026, 10, 25, 16, 0))
        XCTAssertEqual(passed.dueAt, at(2026, 10, 26, 14, 0)); XCTAssertEqual(passed.assumedDay, .tomorrow)
        let stated = classifyAt("老大周五下午2点上课", two, at(2026, 10, 25, 16, 0))
        XCTAssertEqual(stated.assumedDay, .none)
        XCTAssertEqual(stated.dueAt, at(2026, 10, 30, 14, 0))
        // Nothing to anchor the day on: the day is still asked.
        XCTAssertEqual(classify("游泳课", context(children: [tuan])).missing, [.intent])
        let plan = ParentingNaturalInput.applying(.intent(.newArrangement), to: classify("游泳课", context(children: [tuan])), context: context(children: [tuan]), now: now, calendar: calendar)
        XCTAssertEqual(plan.missing, [.date])
        // The card says the day was assumed.
        let ws = workspace(children: [tuan, duo])
        let card = ParentingConfirmCard.make(clear, workspace: ws, remindersEnabled: nil, now: at(2026, 10, 25, 10, 0))
        XCTAssertTrue(card?.blocks.contains(.note("没说日期，先定在今天；不对可以点“手动调整”。", .unknown)) ?? false)
        let cardTomorrow = ParentingConfirmCard.make(passed, workspace: ws, remindersEnabled: nil, now: at(2026, 10, 25, 16, 0))
        XCTAssertTrue(cardTomorrow?.blocks.contains(.note("没说日期，今天这个时间已过，先定在明天；不对可以点“手动调整”。", .unknown)) ?? false)
    }

    // MARK: Cancel (minimal): only cancels a saved arrangement, after a confirm card
    func testCancelFindsTheMatchingArrangementAndAsksForConfirmation() throws {
        let fri = swim(due: at(2026, 10, 30, 17, 0))
        var piano = swim(due: at(2026, 10, 31, 10, 0)); piano.title = "钢琴课"
        let ws = workspace(children: [tuan], arrangements: [fri, piano])
        let ctx = ParentingNaturalContext(workspace: ws)
        let p = classify("取消周五的游泳", ctx)
        XCTAssertEqual(p.kind, .cancel)
        XCTAssertFalse(p.kind == .newArrangement)
        XCTAssertEqual(p.targetID, fri.id)
        XCTAssertTrue(p.isReadyToConfirm)
        XCTAssertNil(ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar))
        let card = try XCTUnwrap(ParentingConfirmCard.make(p, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertEqual(card.kind, .cancel); XCTAssertEqual(card.confirmTitle, "确认取消"); XCTAssertTrue(card.writes)
        XCTAssertEqual(ws.arrangements.first { $0.id == fri.id }?.state, .planned, "a card changes nothing")
        let commands = ParentingNaturalInput.commands(for: p, workspace: ws, ids: .init())
        XCTAssertEqual(commands, [.cancelArrangement(id: fri.id)])
        var next = ws
        for command in commands { next = try next.applying(command, now: now) }
        XCTAssertEqual(next.arrangements.first { $0.id == fri.id }?.state, .cancelled)
        XCTAssertEqual(next.arrangements.first { $0.id == piano.id }?.state, .planned, "only the matching one")
        for text in ["不上钢琴课了", "钢琴课取消"] { XCTAssertEqual(classify(text, ctx).targetID, piano.id, text) }
    }

    func testCancelWithoutAMatchOrWithSeveralMatchesAsksAndNeverBecomesANewArrangement() {
        let empty = context(children: [tuan])
        let none = classify("取消周五的游泳", empty)
        XCTAssertEqual(none.kind, .cancel)
        XCTAssertEqual(none.missing, [.target])
        let ask = ParentingNaturalInput.followUp(for: none, context: empty, calendar: calendar)
        XCTAssertEqual(ask?.question, "没有找到要取消的安排。说具体一点试试，例如：取消周五的游泳。")
        XCTAssertEqual(ask?.options.count, 0, "no way to turn it into a new arrangement")
        XCTAssertTrue(ParentingNaturalInput.commands(for: none, workspace: workspace(children: [tuan]), ids: .init()).isEmpty)
        let a = swim(due: at(2026, 10, 30, 17, 0)), b = swim(due: at(2026, 10, 31, 9, 0))
        let ctx = context(children: [tuan], arrangements: [a, b])
        let two = classify("取消游泳", ctx)
        XCTAssertEqual(two.missing, [.target])
        let choices = ParentingNaturalInput.followUp(for: two, context: ctx, calendar: calendar)
        XCTAssertEqual(choices?.question, "要取消哪一项安排？")
        XCTAssertEqual(choices?.options.count, 2)
        let done = ParentingNaturalInput.applying(choices!.options[0].answer, to: two, context: ctx, now: now, calendar: calendar)
        XCTAssertTrue(done.isReadyToConfirm)
        XCTAssertEqual(done.kind, .cancel)
        // Other kinds are not hijacked by the cancel words.
        XCTAssertEqual(classify("团团周五游泳改到下午6点", ctx).kind, .reschedule)
    }

    func testCancelUsesTheChildTheSiblingWordPointsAt() {
        let mine = swim(due: at(2026, 10, 30, 17, 0), child: tuan), hers = swim(due: at(2026, 10, 30, 17, 0), child: duo)
        let ctx = context(children: [tuan, duo], arrangements: [mine, hers])
        let p = classify("老二取消周五的游泳", ctx)
        XCTAssertEqual(p.targetID, hers.id); XCTAssertEqual(p.childID, duo.id)
    }

    // MARK: Which child: nickname, sibling words (老大/二宝/哥哥…), selected child, only child — never a silent guess
    private let dou = ParentingChild(id: UUID(uuidString: "00000000-0000-0000-0000-0000000000A3")!, name: "豆豆", createdAt: Date(timeIntervalSince1970: 1_700_000_002))

    func testSiblingWordsPointAtTheChildByCreationOrder() {
        let two = context(children: [tuan, duo])
        let a = classify("老大2点上课", two)
        XCTAssertEqual(a.kind, .newArrangement)
        XCTAssertEqual(a.childID, tuan.id)
        XCTAssertEqual(a.title, "上课", "the sibling word is not part of the title")
        let b = classify("老二今天睡得早", two)
        XCTAssertEqual(b.kind, .observation)
        XCTAssertEqual(b.childID, duo.id)
        XCTAssertTrue(b.isReadyToConfirm)
        // Creation order wins over the order of the array.
        let shuffled = context(children: [duo, tuan])
        XCTAssertEqual(classify("老大明天要带水杯", shuffled).childID, tuan.id)
        XCTAssertEqual(classify("老二明天要带水杯", shuffled).childID, duo.id)
    }

    func testSiblingWordsOnlyWorkWhenTheFamilyHasEnoughChildren() {
        XCTAssertEqual(classify("老二今天睡得早", context(children: [tuan])).childID, tuan.id, "one child: bound without asking")
        let two = context(children: [tuan, duo])
        let third = classify("老三今天睡得早", two)
        XCTAssertEqual(third.missing, [.child], "there is no third child: ask, do not guess")
        XCTAssertEqual(ParentingNaturalInput.followUp(for: third, context: two, calendar: calendar)?.options.count, 2)
        let three = context(children: [tuan, duo, dou])
        XCTAssertEqual(classify("老三今天睡得早", three).childID, dou.id)
        XCTAssertEqual(classify("小宝今天睡得早", three).childID, dou.id, "小宝 = the youngest")
        XCTAssertEqual(classify("二宝今天睡得早", three).childID, duo.id)
        XCTAssertEqual(classify("大宝今天睡得早", three).childID, tuan.id)
        XCTAssertEqual(classify("小宝宝今天睡得早", three).missing, [.child], "小宝宝 is a baby, not 小宝")
    }

    func testBrotherAndSisterWordsMapToTheOlderAndTheYoungerChild() {
        let two = context(children: [tuan, duo])
        XCTAssertEqual(classify("哥哥周五下午5点游泳", two).childID, tuan.id)
        XCTAssertEqual(classify("姐姐周五下午5点游泳", two).childID, tuan.id)
        XCTAssertEqual(classify("妹妹今天哭了", two).childID, duo.id)
        XCTAssertEqual(classify("弟弟今天哭了", two).childID, duo.id)
        XCTAssertEqual(classify("妹妹今天哭了", context(children: [tuan])).childID, tuan.id)
    }

    func testSentenceReferenceBeatsSelectedChildAndSelectedChildBeatsAsking() {
        let selected = ParentingNaturalContext(children: [tuan, duo], arrangements: [], activeChildID: tuan.id)
        XCTAssertEqual(classify("老二今天睡得早", selected).childID, duo.id, "a child named in the sentence wins")
        XCTAssertEqual(classify("朵朵今天睡得早", selected).childID, duo.id)
        XCTAssertEqual(classify("今天睡得早", selected).childID, tuan.id, "nothing named: the selected child")
        let none = context(children: [tuan, duo])
        let asked = classify("今天睡得早", none)
        XCTAssertNil(asked.childID)
        XCTAssertEqual(asked.missing, [.child])
        XCTAssertEqual(ParentingNaturalInput.followUp(for: asked, context: none, calendar: calendar)?.options.map(\.id),
                       ["child." + tuan.id.uuidString, "child." + duo.id.uuidString])
    }

    func testTwoChildrenInOneSentenceAskWhichOneAndTheAnswerSticks() {
        let two = context(children: [tuan, duo])
        for text in ["老大和老二今天一起玩了", "团团和朵朵今天一起玩了", "哥哥和妹妹今天一起玩了"] {
            let p = classify(text, two)
            XCTAssertEqual(p.kind, .observation, text)
            XCTAssertEqual(p.missing, [.child], text)
            let ask = ParentingNaturalInput.followUp(for: p, context: two, calendar: calendar)
            XCTAssertEqual(ask?.field, .child)
            XCTAssertEqual(ask?.options.count, 2)
            let done = ParentingNaturalInput.applying(.child(duo.id), to: p, context: two, now: now, calendar: calendar)
            XCTAssertEqual(done.childID, duo.id)
            XCTAssertTrue(done.isReadyToConfirm)
        }
    }

    func testRescheduleFindsTheArrangementOfTheChildTheSiblingWordPointsAt() {
        let mine = swim(due: at(2026, 10, 30, 17, 0), child: tuan)
        let hers = swim(due: at(2026, 10, 30, 17, 0), child: duo)
        let ctx = context(children: [tuan, duo], arrangements: [mine, hers])
        let p = classify("老二周五游泳改到下午6点", ctx)
        XCTAssertEqual(p.kind, .reschedule)
        XCTAssertEqual(p.targetID, hers.id)
        XCTAssertEqual(p.childID, duo.id)
        XCTAssertTrue(p.isReadyToConfirm)
    }

    /// (sentence, record/arrangement verdict, child position in creation order, nil = asked) for a family of three.
    func testChildReferenceCorpus() {
        let kids = [tuan, duo, dou]
        let ctx = context(children: kids)
        let rows: [(String, String, Int?)] = [
            ("老大2点上课", "A", 0), ("老二2点上课", "A", 1), ("老三2点上课", "A", 2),
            ("老大今天睡得早", "R", 0), ("老二今天睡得早", "R", 1), ("老三今天睡得早", "R", 2),
            ("大宝明天要带水杯", "A", 0), ("二宝明天要带水杯", "A", 1), ("小宝明天要带水杯", "A", 2),
            ("哥哥周五下午5点游泳", "A", 0), ("姐姐周五下午5点游泳", "A", 0), ("弟弟今天哭了", "R", 2), ("妹妹今天哭了", "R", 2),
            ("团团今天自己穿了鞋", "R", 0), ("朵朵周五下午5点游泳", "A", 1), ("豆豆昨天发烧了", "R", 2),
            ("老大和老二今天一起玩了", "R", nil), ("团团和豆豆今天一起玩了", "R", nil), ("哥哥和弟弟周五下午5点游泳", "A", nil),
            ("今天睡得早", "R", nil), ("明天要带水杯", "A", nil), ("老五今天睡得早", "R", nil),
            ("小宝宝今天睡得早", "R", nil), ("今天他自己穿了鞋", "R", nil),
        ]
        var wrong: [String] = []
        for (text, kindVerdict, position) in rows {
            let p = classify(text, ctx)
            let kind: String = p.kind == .observation ? "R" : (p.kind == .newArrangement ? "A" : "?")
            let child = p.childID.flatMap { id in kids.firstIndex { $0.id == id } }
            if kind != kindVerdict || child != position { wrong.append("\(text): \(kind)/\(String(describing: child))") }
        }
        XCTAssertEqual(wrong, [])
    }

    // MARK: Missing-field follow-up (one question, only what is missing)
    func testAskingForTheChildOnlyWhenSeveralChildrenAndNoneIsNamed() {
        let ctx = context(children: [tuan, duo])
        let p = classify("周五下午5点游泳", ctx)
        XCTAssertEqual(p.missing, [.child])
        let ask = ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)
        XCTAssertEqual(ask?.field, .child)
        XCTAssertEqual(ask?.options.map(\.label), [.verbatim("团团"), .verbatim("朵朵")])
        let answered = ParentingNaturalInput.applying(.child(duo.id), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertTrue(answered.isReadyToConfirm)
        XCTAssertEqual(answered.childID, duo.id)
        XCTAssertEqual(answered.dueAt, at(2026, 10, 30, 17, 0), "already clear fields are not asked or changed again")
        XCTAssertNil(ParentingNaturalInput.followUp(for: answered, context: ctx, calendar: calendar))
    }

    func testNamedChildIsNeverAsked() {
        let p = classify("朵朵明天下午3点钢琴课", context(children: [tuan, duo]))
        XCTAssertEqual(p.childID, duo.id)
        XCTAssertTrue(p.missing.isEmpty)
    }

    func testBareScheduleNounAsksRecordOrReminderThenDateThenDefaultsTheHour() {
        let ctx = context(children: [tuan])
        var p = classify("游泳课", ctx)
        XCTAssertEqual(p.kind, .unknown)
        XCTAssertTrue(p.ambiguousIntent)
        var ask = ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)
        XCTAssertEqual(ask?.field, .intent)
        XCTAssertEqual(ask?.options.map(\.id), ["intent.observation", "intent.newArrangement"], "记下来 on the left, 安排提醒 on the right")
        XCTAssertEqual(ask?.options.map(\.label), [.key("记下来"), .key("安排提醒")])
        p = ParentingNaturalInput.applying(.intent(.newArrangement), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(p.kind, .newArrangement)
        ask = ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)
        XCTAssertEqual(ask?.field, .date, "never a question about the hour")
        p = ParentingNaturalInput.applying(.text("周五"), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(p.kind, .newArrangement, "the choice stays locked")
        XCTAssertTrue(p.isReadyToConfirm)
        XCTAssertEqual(p.title, "游泳课")
        XCTAssertEqual(p.dueAt, at(2026, 10, 30, 9, 0))
        XCTAssertTrue(p.defaultedTime)
        p = ParentingNaturalInput.applying(.text("下午5点"), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(p.dueAt, at(2026, 10, 30, 17, 0), "a stated clock replaces the default")
        XCTAssertFalse(p.defaultedTime)
    }

    func testAnUnclearHourOffersMorningAndAfternoonInsteadOfGuessing() {
        let ctx = context(children: [tuan])
        let p = classify("周五5点游泳", ctx)
        XCTAssertNil(p.dueAt, "no silent default hour")
        XCTAssertEqual(p.missing, [.time])
        let ask = ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)
        XCTAssertEqual(ask?.options.map(\.label), [.dateTime(at(2026, 10, 30, 5, 0)), .dateTime(at(2026, 10, 30, 17, 0))])
        let done = ParentingNaturalInput.applying(ask!.options[1].answer, to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(done.dueAt, at(2026, 10, 30, 17, 0))
        XCTAssertTrue(done.isReadyToConfirm)
    }

    func testRescheduleWithAnUnclearHourOffersTheOriginalHalfOfTheDayFirst() {
        let existing = swim(due: at(2026, 10, 30, 17, 0))
        let ctx = context(children: [tuan], arrangements: [existing])
        let p = classify("团团周五游泳改到六点", ctx)
        XCTAssertEqual(p.kind, .reschedule)
        XCTAssertEqual(p.missing, [.time])
        let ask = ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)
        XCTAssertEqual(ask?.options.first?.label, .dateTime(at(2026, 10, 30, 18, 0)))
    }

    func testRescheduleToTheSameTimeAsksAgainAndTwoCandidatesAskWhichOne() {
        let a = swim(due: at(2026, 10, 30, 17, 0))
        var b = swim(due: at(2026, 10, 31, 9, 0)); b.title = "游泳课"
        let ctx = context(children: [tuan], arrangements: [a, b])
        let same = classify("游泳改到下午5点", context(children: [tuan], arrangements: [a]))
        XCTAssertEqual(same.missing, [.time], "an unchanged time is not a reschedule")
        let ambiguous = classify("游泳改到下午6点", ctx)
        XCTAssertEqual(ambiguous.missing, [.target])
        XCTAssertEqual(ParentingNaturalInput.followUp(for: ambiguous, context: ctx, calendar: calendar)?.options.count, 2)
    }

    func testUnknownIntentAsksOnceAndKeepsTheAnswer() {
        let ctx = context(children: [tuan])
        let p = classify("嗯", ctx)
        XCTAssertEqual(p.missing, [.intent])
        XCTAssertFalse(p.ambiguousIntent, "chit-chat is not a conflict")
        let ask = ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)
        XCTAssertEqual(ask?.options.count, 2)
        let obs = ParentingNaturalInput.applying(.intent(.observation), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(obs.kind, .observation)
        XCTAssertEqual(obs.observationText, "嗯")
    }

    func testEmptyAndOversizedInputNeverBecomeAProposal() {
        XCTAssertFalse(classify("   ", context(children: [tuan])).isReadyToConfirm)
        XCTAssertFalse(classify(String(repeating: "游", count: 3001), context(children: [tuan])).isReadyToConfirm)
    }

    // MARK: Confirm card and write path
    func testCardAppearsOnlyWhenReadyAndWritesNothingUntilCommandsAreApplied() throws {
        let ctx = context(children: [tuan])
        let ws = workspace(children: [tuan])
        let missing = classify("游泳课", ctx)
        XCTAssertNil(ParentingConfirmCard.make(missing, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertTrue(ParentingNaturalInput.commands(for: missing, workspace: ws, ids: .init()).isEmpty)

        let ready = classify("团团周五下午5点游泳", ctx)
        let card = try XCTUnwrap(ParentingConfirmCard.make(ready, workspace: ws, remindersEnabled: true, now: now))
        XCTAssertTrue(card.writes)
        XCTAssertEqual(card.confirmTitle, "确认安排")
        XCTAssertTrue(card.blocks.contains(.quote(label: "你说的原话", text: "团团周五下午5点游泳")))
        XCTAssertTrue(card.blocks.contains(.note("提醒按你的设置发送：每天最多 2 条，21:00–08:00 不打扰。", .normal)))
        XCTAssertEqual(ws.arrangements.count, 0, "building a card changes nothing")

        let ids = ParentingNaturalInput.WriteIDs()
        var next = ws
        for command in ParentingNaturalInput.commands(for: ready, workspace: ws, ids: ids) { next = try next.applying(command, now: now) }
        XCTAssertEqual(next.arrangements.count, 1)
        XCTAssertEqual(next.arrangements[0].state, .planned)
        XCTAssertEqual(next.arrangements[0].dueAt, at(2026, 10, 30, 17, 0))
        XCTAssertEqual(next.arrangements[0].sourceText, "团团周五下午5点游泳")
    }

    func testObservationCommandStoresOnlyAnObservation() throws {
        let ws = workspace(children: [tuan])
        let p = classify("记一下，今天他自己收好书包了", context(children: [tuan]))
        var next = ws
        for command in ParentingNaturalInput.commands(for: p, workspace: ws, ids: .init()) { next = try next.applying(command, now: now) }
        XCTAssertEqual(next.records.count, 1)
        XCTAssertEqual(next.records[0].kind, .observation)
        XCTAssertEqual(next.records[0].text, "今天他自己收好书包了")
        XCTAssertTrue(next.arrangements.isEmpty)
    }

    func testRescheduleCardShowsBeforeAfterAndSameTimeConflictsAreAcknowledgedOnConfirm() throws {
        let existing = swim(due: at(2026, 10, 30, 17, 0))
        var other = swim(due: at(2026, 10, 30, 18, 0)); other.title = "钢琴课"
        let ws = workspace(children: [tuan], arrangements: [existing, other])
        let p = classify("团团周五游泳改到下午6点", ParentingNaturalContext(workspace: ws))
        let card = try XCTUnwrap(ParentingConfirmCard.make(p, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertTrue(card.blocks.contains(.change(label: "时间", before: existing.dueAt, after: at(2026, 10, 30, 18, 0))))
        XCTAssertTrue(card.blocks.contains { if case .conflicts(_, let items) = $0 { return items.map(\.title) == ["钢琴课"] } else { return false } })
        var next = ws
        for command in ParentingNaturalInput.commands(for: p, workspace: ws, ids: .init()) { next = try next.applying(command, now: now) }
        XCTAssertEqual(next.arrangements.first { $0.id == existing.id }?.dueAt, at(2026, 10, 30, 18, 0))
        XCTAssertEqual(next.editHistory.count, 1, "the change is recorded and can be undone")
        XCTAssertEqual(next.arrangements.first { $0.id == other.id }?.dueAt, other.dueAt, "other arrangements are untouched")
    }

    func testExplicitArchiveStatementBecomesAConfirmCardAndALongTermFactOnlyAfterCommands() throws {
        let ctx = context(children: [tuan, duo])
        let ws = workspace(children: [tuan, duo])
        let p = classify("记入档案团团对花生过敏", ctx)
        XCTAssertEqual(p.kind, .profileFact)
        XCTAssertTrue(p.isReadyToConfirm)
        XCTAssertEqual(p.profileFact?.scope, .child(tuan.id))
        XCTAssertEqual(p.profileFact?.kind, .sensitive)
        XCTAssertEqual(p.profileFact?.confidence, .high)
        XCTAssertNotEqual(p.profileFact?.kind, .alias)
        let card = try XCTUnwrap(ParentingConfirmCard.make(p, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertEqual(card.kind, .profileFact)
        XCTAssertTrue(card.writes)
        XCTAssertEqual(card.confirmTitle, "确认记入档案")
        XCTAssertTrue(card.blocks.contains(.quote(label: "你说的原话", text: "记入档案团团对花生过敏")))
        XCTAssertEqual(ws.profile.facts.count, 0, "building the card writes nothing")

        let ids = ParentingNaturalInput.WriteIDs(record: UUID(uuidString: "00000000-0000-4000-8000-0000000000aa")!)
        let commands = ParentingNaturalInput.commands(for: p, workspace: ws, ids: ids)
        XCTAssertEqual(commands.count, 2)
        var next = ws
        next = try next.applying(commands[0], now: now)
        XCTAssertTrue(next.profile.usableFacts.isEmpty)
        XCTAssertTrue(next.arrangements.isEmpty)
        XCTAssertTrue(next.records.isEmpty)
        next = try next.applying(commands[1], now: now.addingTimeInterval(5))
        XCTAssertEqual(next.profile.usableFacts(for: .child(tuan.id)).map(\.text), ["团团对花生过敏"])
        XCTAssertTrue(next.profile.usableFacts(for: .child(duo.id)).isEmpty)
        XCTAssertEqual(next.profile.facts[0].source.channel, .confirmCard)
        XCTAssertLessThanOrEqual(next.profile.facts[0].source.excerpt?.count ?? 0, ParentingProfile.maxExcerptLength)
    }

    func testUncertainArchiveStatementsAndSiblingLabelsDoNotWrite() throws {
        let both = context(children: [tuan, duo])
        let ws = workspace(children: [tuan, duo])
        let unnamed = classify("记入档案对花生过敏", both)
        XCTAssertEqual(unnamed.kind, .profileFact)
        XCTAssertFalse(unnamed.isReadyToConfirm)
        XCTAssertNil(unnamed.profileFact)
        XCTAssertNil(ParentingConfirmCard.make(unnamed, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertTrue(ParentingNaturalInput.commands(for: unnamed, workspace: ws, ids: .init()).isEmpty)
        let ask = ParentingNaturalInput.followUp(for: unnamed, context: both, calendar: calendar)
        XCTAssertEqual(ask?.field, .content)
        XCTAssertEqual(ask?.options.count, 0, "which-child picking is not this card")

        for text in ["记入档案老大对花生过敏", "记在我们家二宝对花生过敏"] {
            let p = classify(text, both)
            XCTAssertFalse(p.isReadyToConfirm, text)
            XCTAssertNil(p.profileFact, text)
            XCTAssertTrue(ParentingNaturalInput.commands(for: p, workspace: ws, ids: .init()).isEmpty, text)
            let read = try XCTUnwrap(ParentingProfileFactReader.read(text, context: both, now: now))
            XCTAssertEqual(read.confidence, .low, text)
            XCTAssertNotEqual(read.scope, .child(tuan.id), text)
            XCTAssertNotEqual(read.scope, .child(duo.id), text)
            XCTAssertTrue(read.longTermCommands(userConfirmed: true).isEmpty, text)
        }

        let only = context(children: [tuan])
        let sibling = classify("记入档案老大对花生过敏", only)
        XCTAssertFalse(sibling.isReadyToConfirm, "a sibling label is not resolved even when there is one child")
        XCTAssertTrue(ParentingNaturalInput.commands(for: sibling, workspace: workspace(children: [tuan]), ids: .init()).isEmpty)
    }

    func testArchiveLeadDoesNotStealAnOrdinaryArrangementOrABareRemember() throws {
        let ctx = context(children: [tuan])
        XCTAssertEqual(classify("团团周五下午5点游泳", ctx).kind, .newArrangement)
        XCTAssertNotEqual(classify("记住团团周五下午5点游泳", ctx).kind, .profileFact)
        let household = classify("记在我们家周三奶奶接园", ctx)
        XCTAssertEqual(household.kind, .profileFact)
        XCTAssertEqual(household.profileFact?.scope, .family)
        XCTAssertEqual(household.profileFact?.kind, .pickup)
        XCTAssertTrue(household.isReadyToConfirm)
    }

    func testOneChildArchiveWithoutANameStillShowsThatChildOnTheCard() throws {
        let ctx = context(children: [tuan])
        let ws = workspace(children: [tuan])
        let p = classify("记入档案对花生过敏", ctx)
        XCTAssertEqual(p.profileFact?.scope, .child(tuan.id))
        XCTAssertEqual(p.profileFact?.confidence, .medium)
        let card = try XCTUnwrap(ParentingConfirmCard.make(p, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertTrue(card.blocks.contains { block in
            guard case .fields(let fields) = block else { return false }
            return fields.contains { $0.label == "范围" && $0.value == .text("团团") }
        })
        XCTAssertEqual(ws.profile, .empty)
    }

    // MARK: Today timeline and reminder limits
    func testTodayTimelineOrdersByTimeSeparatesPendingAndMarksNow() {
        let early = swim(due: at(2026, 10, 25, 8, 30)), later = swim(due: at(2026, 10, 25, 17, 0))
        var twin = swim(due: at(2026, 10, 25, 17, 0)); twin.title = "钢琴课"
        let draft = swim(due: at(2026, 10, 27, 9, 0), state: .draft)
        let overdue = swim(due: at(2026, 10, 23, 9, 0))
        let tomorrow = swim(due: at(2026, 10, 26, 9, 0))
        let cancelled = swim(due: at(2026, 10, 25, 12, 0), state: .cancelled)
        let ws = workspace(children: [tuan], arrangements: [later, twin, early, draft, overdue, tomorrow, cancelled])
        let timeline = ParentingTodayTimeline.build(workspace: ws, now: now, calendar: calendar)
        XCTAssertEqual(timeline.pending.map(\.id), [draft.id])
        XCTAssertEqual(timeline.overdue.map(\.id), [overdue.id])
        XCTAssertEqual(timeline.upcoming.map(\.id), [tomorrow.id])
        XCTAssertEqual(timeline.entries.count, 3, "cancelled items never show")
        XCTAssertEqual(timeline.entries.first?.id, early.id)
        XCTAssertEqual(timeline.nowIndex, 1, "the now marker sits after the 08:30 item")
        XCTAssertEqual(timeline.entries.filter(\.sameTimeConflict).count, 2)
        XCTAssertEqual(timeline.summary.pending, 1)
        XCTAssertEqual(timeline.summary.today, 3)
        XCTAssertNotNil(timeline.summary.next)
    }

    func testGrowthTimelineGroupsObservationsAndCompletionsByDay() {
        let d1 = at(2026, 10, 24, 20, 0), d2 = at(2026, 10, 25, 9, 0)
        func record(_ kind: ParentingRecordKind, _ when: Date, _ text: String) -> ParentingRecord {
            .init(id: UUID(), childID: tuan.id, goalID: nil, arrangementID: nil, kind: kind, text: text, sourceText: text, createdAt: when, updatedAt: when)
        }
        let ws = ParentingWorkspace(revision: 1, children: [tuan], records: [record(.observation, d1, "a"), record(.observation, d2, "b"), record(.observation, d2.addingTimeInterval(60), "c")])
        let all = ParentingGrowthTimeline.build(workspace: ws, calendar: calendar)
        XCTAssertEqual(all.days.count, 2)
        XCTAssertEqual(all.days.first?.records.map(\.text), ["c", "b"], "newest day and newest record first")
        XCTAssertTrue(ParentingGrowthTimeline.build(workspace: ws, filter: .completion, calendar: calendar).isEmpty)
    }

    func testReminderLimitAndQuietHoursStayAtTwoAndTwentyOneToEight() {
        XCTAssertEqual(ParentingReminderPlanner.maxPerDay, 2)
        XCTAssertEqual(ParentingReminderPlanner.quietStartHour, 21)
        XCTAssertEqual(ParentingReminderPlanner.quietEndHour, 8)
        let items = [at(2026, 10, 26, 10, 0), at(2026, 10, 26, 14, 0), at(2026, 10, 26, 16, 0)].map { swim(due: $0) }
        let ws = workspace(children: [tuan], arrangements: items)
        let plan = ParentingReminderPlanner.plan(workspace: ws, settings: .init(enabled: true, lead: .minutes30), now: now, calendar: calendar)
        XCTAssertEqual(plan.reminders.count, 2)
        XCTAssertEqual(plan.skipped.values.filter { $0 == .dailyLimit }.count, 1)
        XCTAssertTrue(plan.reminders.allSatisfy { !ParentingReminderPlanner.isQuiet($0.fireAt, calendar: calendar) })
    }
}
