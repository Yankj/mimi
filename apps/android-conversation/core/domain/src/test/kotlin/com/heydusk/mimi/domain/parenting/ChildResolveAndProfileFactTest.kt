package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.parenting.T.calendar
import com.heydusk.mimi.domain.parenting.T.duo
import com.heydusk.mimi.domain.parenting.T.now
import com.heydusk.mimi.domain.parenting.T.tuan
import com.heydusk.mimi.domain.common.DomainError
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS #147（认娃 / 是谁的事）与 #148（档案确认 → 长期事实）的移植用例，对齐 iOS main f3d2f64。 */
class ChildResolveAndProfileFactTest {
    private fun ctx(children: List<ParentingChild> = listOf(tuan, duo), active: UUID? = null) = ParentingNaturalContext(children, emptyList(), active)
    private fun classify(text: String, c: ParentingNaturalContext = ctx()) = ParentingNaturalInput.classify(text, c, now, calendar)
    private fun ws(children: List<ParentingChild> = listOf(tuan, duo)) = T.workspace(children)

    // MARK: #147

    @Test fun recordForASiblingWordAfterGiveDropsTheSubjectFromTheBody() {
        val p = classify("给二宝记一笔长高了")
        assertEquals(ParentingIntentKind.Observation, p.kind)
        assertEquals(duo.id, p.childID)
        assertEquals("长高了", p.observationText, "给二宝 and 记一笔 are not part of the record")
        assertTrue(p.isReadyToConfirm)
        val named = classify("帮团团记一下今天自己穿鞋了")
        assertEquals(ParentingIntentKind.Observation, named.kind)
        assertEquals(tuan.id, named.childID)
        assertEquals("今天自己穿鞋了", named.observationText)
        // 句中排行优先于页面选中的孩子。
        assertEquals(duo.id, classify("给二宝记一笔长高了", ctx(active = tuan.id)).childID)
        assertEquals(tuan.id, classify("老大2点上课", ctx(active = duo.id)).childID)
    }

    @Test fun siblingWordsFollowCreationOrderAndNeedEnoughChildren() {
        val shuffled = ctx(listOf(duo, tuan))
        assertEquals(duo.id, classify("老二明天要带水杯", shuffled).childID, "creation order, not list order")
        assertEquals(tuan.id, classify("哥哥明天要带水杯", shuffled).childID)
        assertEquals(duo.id, classify("妹妹明天要带水杯", shuffled).childID, "youngest")
        assertEquals(tuan.id, classify("老二今天睡得早", ctx(listOf(tuan))).childID, "one child: bound without asking")
        val title = classify("老大明天下午2点上课")
        assertEquals("上课", title.title)
    }

    @Test fun unsureWhoAsksWithAllChildrenAndNothingPreselected() {
        val p = classify("明天下午3点打疫苗")
        assertNull(p.childID)
        assertTrue(ParentingMissingField.Child in p.missing)
        val ask = assertNotNull(ParentingNaturalInput.followUp(p, ctx(), calendar))
        assertEquals(ParentingMissingField.Child, ask.field)
        assertEquals(2, ask.options.size)
        val both = classify("团团和朵朵明天下午3点打疫苗")
        assertTrue(ParentingMissingField.Child in both.missing, "several named children are never guessed")
    }

    @Test fun questionPickerPreselectsNobody() {
        val ids = listOf(tuan.id, duo.id)
        assertEquals(ParentingChildSwitcherSelection.All, ParentingChildSwitcherRules.resolve(null, ids, allowsAll = false, fallsBackToFirst = false))
        assertEquals(ParentingChildSwitcherSelection.Child(tuan.id), ParentingChildSwitcherRules.resolve(null, ids, allowsAll = false))
        assertEquals(ParentingChildSwitcherSelection.Child(duo.id), ParentingChildSwitcherRules.resolve(duo.id, ids, allowsAll = false, fallsBackToFirst = false))
        assertEquals(ParentingChildSwitcherSelection.All, ParentingChildSwitcherRules.resolve(null, ids, allowsAll = true))
    }

    @Test fun answeringTheChildKeepsTheRestOfTheSentence() {
        val p = classify("老大下午2点上课")
        val changed = ParentingNaturalInput.applying(ParentingNaturalAnswer.Child(duo.id), p, ctx(), now, calendar)
        assertEquals(duo.id, changed.childID)
        assertEquals("上课", changed.title)
        assertTrue(changed.isReadyToConfirm)
    }

    // MARK: #148

    @Test fun explicitArchiveForANamedChildBecomesACardAndWritesOnlyAfterConfirm() {
        val p = classify("记入档案：团团对花生过敏")
        assertEquals(ParentingIntentKind.ProfileFact, p.kind)
        val fact = assertNotNull(p.profileFact)
        assertEquals(ParentingProfileScope.child(tuan.id), fact.scope)
        assertEquals(ParentingProfileFactKind.Sensitive, fact.kind)
        assertEquals("团团对花生过敏", fact.text)
        val card = assertNotNull(ParentingConfirmCard.make(p, ws(), null, now))
        assertEquals("确认记入档案", card.confirmTitle)
        // 只看卡片不写入：账本仍然没有事实。
        val before = ws()
        assertTrue(before.profile.facts.isEmpty())
        val cmds = ParentingNaturalInput.commands(p, before, ParentingNaturalInput.WriteIDs())
        assertEquals(2, cmds.size)
        var w = before
        for (c in cmds) w = w.applying(c, now)
        val usable = w.profile.usableFacts(ParentingProfileScope.child(tuan.id))
        assertEquals(1, usable.size)
        assertEquals("团团对花生过敏", usable[0].text)
        assertTrue(w.arrangements.isEmpty() && w.records.isEmpty())
    }

    @Test fun familyArchiveIsAFamilyFactNotTodaysArrangement() {
        val p = classify("记在我们家周三奶奶接园")
        assertEquals(ParentingIntentKind.ProfileFact, p.kind)
        val fact = assertNotNull(p.profileFact)
        assertEquals(ParentingProfileScope.FAMILY, fact.scope)
        assertEquals(ParentingProfileFactKind.Pickup, fact.kind)
        assertEquals("周三奶奶接园", fact.text)
        var w = ws()
        for (c in ParentingNaturalInput.commands(p, w, ParentingNaturalInput.WriteIDs())) w = w.applying(c, now)
        assertEquals(1, w.profile.usableFacts(ParentingProfileScope.FAMILY).size)
        assertTrue(w.arrangements.isEmpty(), "not an arrangement")
        assertEquals("我们家", ((ParentingConfirmCard.make(p, ws(), null, now)!!.blocks[1] as ParentingConfirmCard.Block.Fields).fields[0].value as ParentingConfirmCard.Value.Text).text)
    }

    @Test fun ordinarySentencesStayArrangementsOrRecords() {
        assertEquals(ParentingIntentKind.NewArrangement, classify("团团周五下午5点游泳").kind)
        val bare = classify("记住团团周五下午5点游泳")
        assertEquals(ParentingIntentKind.NewArrangement, bare.kind, "a bare 记住 is not an archive lead")
        assertNull(bare.profileFact)
        assertEquals(ParentingIntentKind.Observation, classify("给二宝记一笔长高了").kind)
    }

    @Test fun uncertainArchiveStatementsOfferNoWritingCard() {
        for (text in listOf("记入档案：老大对花生过敏", "长期记住二宝怕黑", "记入档案：喜欢恐龙", "记入档案", "记入档案：团团和朵朵都喜欢画画")) {
            val p = classify(text)
            assertEquals(ParentingIntentKind.ProfileFact, p.kind, text)
            assertNull(p.profileFact, text)
            assertFalse(p.isReadyToConfirm, text)
            assertNull(ParentingConfirmCard.make(p, ws(), null, now), text)
            assertTrue(ParentingNaturalInput.commands(p, ws(), ParentingNaturalInput.WriteIDs()).isEmpty(), text)
            val ask = assertNotNull(ParentingNaturalInput.followUp(p, ctx(), calendar))
            assertEquals("这句话还不够确定，不会记成长期事实。补上孩子的昵称，或说明是我们家的事。", ask.question)
        }
    }

    @Test fun oneChildFamilyMayOfferAnUnnamedFactAtMediumConfidence() {
        val p = classify("记入档案：喜欢恐龙", ctx(listOf(tuan)))
        val fact = assertNotNull(p.profileFact)
        assertEquals(ParentingProfileScope.child(tuan.id), fact.scope)
        assertEquals(ParentingProfileConfidence.Medium, fact.confidence)
    }

    @Test fun excerptIsCappedAndLowConfidenceCanNeverBeConfirmed() {
        val long = "记入档案：团团" + "喜欢".repeat(40)
        val fact = assertNotNull(classify(long).profileFact)
        assertTrue(fact.excerpt!!.length <= ParentingProfile.MAX_EXCERPT_LENGTH)
        val low = fact.copy(confidence = ParentingProfileConfidence.Low)
        assertTrue(low.longTermCommands(userConfirmed = true).isEmpty())
        assertTrue(fact.longTermCommands(userConfirmed = false).isEmpty(), "先不记 writes nothing")
        // 账本自身也拒绝确认低置信条目。
        val cmds = low.copy(confidence = ParentingProfileConfidence.High).longTermCommands(true)
        val added = (cmds[0] as ParentingCommand.AddProfileFact).fact.copy(confidence = ParentingProfileConfidence.Low)
        val w = ws().applying(ParentingCommand.AddProfileFact(added), now)
        assertTrue(w.profile.usableFacts.isEmpty(), "a candidate is never usable")
        assertFailsWith<DomainError> { w.applying(ParentingCommand.ConfirmProfileFact(added.id), now) }
    }

    @Test fun deletingAChildRemovesItsFactsAndOldLedgersDecode() {
        var w = ws()
        for (c in ParentingNaturalInput.commands(classify("记入档案：团团对花生过敏"), w, ParentingNaturalInput.WriteIDs())) w = w.applying(c, now)
        assertEquals(1, w.profile.facts.size)
        assertTrue(w.applying(ParentingCommand.DeleteChild(tuan.id), now).profile.facts.isEmpty())
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }
        val old = json.decodeFromString(ParentingWorkspace.serializer(), """{"schemaVersion":1,"revision":0}""")
        assertTrue(old.profile.facts.isEmpty())
        val round = json.decodeFromString(ParentingWorkspace.serializer(), json.encodeToString(ParentingWorkspace.serializer(), w))
        assertEquals(w, round)
    }
}
