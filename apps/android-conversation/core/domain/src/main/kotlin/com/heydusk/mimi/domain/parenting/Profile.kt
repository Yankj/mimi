@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.InstantSerializer
import com.heydusk.mimi.domain.common.UuidSerializer
import com.heydusk.mimi.domain.common.graphemeCount
import com.heydusk.mimi.domain.common.prefixGraphemes
import com.heydusk.mimi.domain.common.trimSwift
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** 本机家庭档案（iOS `ParentingProfile`，#148）。候选从不影响规划；只有家长在确认卡上确认过的才算数。 */
@Serializable
enum class ParentingProfileFactKind(val archiveTitle: String) {
    @SerialName("alias") Alias("称呼"),
    @SerialName("recurring") Recurring("重复安排"),
    @SerialName("routine") Routine("习惯"),
    @SerialName("pickup") Pickup("接送"),
    @SerialName("preference") Preference("偏好"),
    @SerialName("member") Member("家人"),
    @SerialName("ageStage") AgeStage("年龄阶段"),
    @SerialName("sleep") Sleep("睡眠"),
    @SerialName("interest") Interest("兴趣"),
    @SerialName("trait") Trait("特点"),
    @SerialName("sensitive") Sensitive("需留意"),
}

@Serializable
enum class ParentingProfileFactStatus { @SerialName("candidate") Candidate, @SerialName("confirmed") Confirmed, @SerialName("dismissed") Dismissed, @SerialName("stale") Stale }

@Serializable
enum class ParentingProfileSourceChannel { @SerialName("explicitStatement") ExplicitStatement, @SerialName("confirmCard") ConfirmCard, @SerialName("manualEdit") ManualEdit }

@Serializable
enum class ParentingProfileConfidence { @SerialName("low") Low, @SerialName("medium") Medium, @SerialName("high") High }

/** 家庭范围（childID == null）或某个孩子。 */
@Serializable
data class ParentingProfileScope(val childID: UUID? = null) {
    val isFamily: Boolean get() = childID == null
    companion object {
        val FAMILY = ParentingProfileScope(null)
        fun child(id: UUID) = ParentingProfileScope(id)
    }
}

@Serializable
data class ParentingProfileSource(val channel: ParentingProfileSourceChannel, val excerpt: String? = null, val firstSeenAt: Instant)

@Serializable
data class ParentingProfileFact(
    val id: UUID,
    val scope: ParentingProfileScope,
    val kind: ParentingProfileFactKind,
    val payload: String,
    val text: String,
    val status: ParentingProfileFactStatus,
    val source: ParentingProfileSource,
    val confidence: ParentingProfileConfidence,
    val evidenceCount: Int = 1,
    val lastSeenAt: Instant,
    val lastConfirmedAt: Instant? = null,
    val userConfirmed: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** 只有确认过、且家长点过确认的事实才能用。 */
    val isUsable: Boolean get() = userConfirmed && status == ParentingProfileFactStatus.Confirmed
}

@Serializable
data class ParentingProfile(val facts: List<ParentingProfileFact> = emptyList(), val learningEnabled: Boolean = true) {
    val usableFacts: List<ParentingProfileFact> get() = facts.filter { it.isUsable }

    /** 某个范围里确认过的事实，旧的在前。候选、低置信都不在。 */
    fun usableFacts(scope: ParentingProfileScope): List<ParentingProfileFact> =
        usableFacts.filter { it.scope == scope }.sortedBy { it.createdAt }

    companion object {
        const val MAX_FAMILY_FACTS = 40
        const val MAX_CHILD_FACTS = 60
        const val MAX_TEXT_LENGTH = 120
        const val MAX_PAYLOAD_LENGTH = 240
        const val MAX_EXCERPT_LENGTH = 40
    }
}

/** 端上提出的一条档案事实，还不是账本行。低置信 = 不确定：不能上写入卡，也不能成为长期事实。 */
data class ParentingProfileFactProposal(
    var id: UUID,
    val scope: ParentingProfileScope,
    val kind: ParentingProfileFactKind,
    val payload: String,
    val text: String,
    val confidence: ParentingProfileConfidence,
    val excerpt: String?,
    val seenAt: Instant,
) {
    val trimmedText: String get() = text.trimSwift()
    val trimmedPayload: String get() = payload.trimSwift()

    val canOfferConfirmCard: Boolean get() =
        confidence != ParentingProfileConfidence.Low && trimmedText.isNotEmpty() &&
            trimmedText.graphemeCount() <= ParentingProfile.MAX_TEXT_LENGTH &&
            trimmedPayload.graphemeCount() <= ParentingProfile.MAX_PAYLOAD_LENGTH

    /** 家长确认卡片之后才有命令：先存候选，再标确认。低置信或称呼绑定一律为空。 */
    fun longTermCommands(userConfirmed: Boolean): List<ParentingCommand> {
        if (!userConfirmed || !canOfferConfirmCard || kind == ParentingProfileFactKind.Alias) return emptyList()
        val ex = excerpt?.prefixGraphemes(ParentingProfile.MAX_EXCERPT_LENGTH)?.ifEmpty { null }
        val fact = ParentingProfileFact(
            id = id, scope = scope, kind = kind,
            payload = trimmedPayload.prefixGraphemes(ParentingProfile.MAX_PAYLOAD_LENGTH),
            text = trimmedText.prefixGraphemes(ParentingProfile.MAX_TEXT_LENGTH),
            status = ParentingProfileFactStatus.Candidate,
            source = ParentingProfileSource(ParentingProfileSourceChannel.ConfirmCard, ex, seenAt),
            confidence = confidence, lastSeenAt = seenAt, userConfirmed = false, createdAt = seenAt, updatedAt = seenAt,
        )
        return listOf(ParentingCommand.AddProfileFact(fact), ParentingCommand.ConfirmProfileFact(id))
    }
}

/** 把一句显式的「记入档案」结构化。不联网、不用模型、不把排行词映射成孩子。 */
object ParentingProfileFactReader {
    fun read(raw: String, context: ParentingNaturalContext, now: Instant, id: UUID = UUID.randomUUID()): ParentingProfileFactProposal? {
        val lexicon = NaturalLexicon
        val text = lexicon.normalized(raw)
        if (!lexicon.hasArchiveLead(text)) return null
        val body = cleanBody(text)
        val clipped = body.prefixGraphemes(ParentingProfile.MAX_TEXT_LENGTH)
        val payload = body.prefixGraphemes(ParentingProfile.MAX_PAYLOAD_LENGTH)
        val excerpt = text.prefixGraphemes(ParentingProfile.MAX_EXCERPT_LENGTH)
        val (scope, confidence) = resolve(text, context, clipped.isNotEmpty())
        return ParentingProfileFactProposal(id, scope, kind(body), payload, clipped, confidence, excerpt, now)
    }

    /** 昵称优先；排行词从不映射到孩子；只有一个孩子且没点名时可以上卡；多个孩子不行。 */
    private fun resolve(text: String, context: ParentingNaturalContext, hasBody: Boolean): Pair<ParentingProfileScope, ParentingProfileConfidence> {
        val named = context.children.filter { it.name.isNotEmpty() && text.contains(it.name) }
        if (named.size == 1 && hasBody) return ParentingProfileScope.child(named[0].id) to ParentingProfileConfidence.High
        if (named.size > 1 || NaturalLexicon.mentionsSiblingLabel(text) || !hasBody) return ParentingProfileScope.FAMILY to ParentingProfileConfidence.Low
        if (NaturalLexicon.archiveFamilyWords.any { text.contains(it) }) return ParentingProfileScope.FAMILY to ParentingProfileConfidence.High
        if (context.children.size == 1) return ParentingProfileScope.child(context.children[0].id) to ParentingProfileConfidence.Medium
        return ParentingProfileScope.FAMILY to ParentingProfileConfidence.Low
    }

    private fun kind(body: String): ParentingProfileFactKind {
        val l = NaturalLexicon
        fun has(words: List<String>) = words.any { body.contains(it) }
        return when {
            has(l.archiveSensitiveWords) -> ParentingProfileFactKind.Sensitive
            has(l.archivePickupWords) -> ParentingProfileFactKind.Pickup
            has(l.archiveSleepWords) -> ParentingProfileFactKind.Sleep
            has(l.archiveInterestWords) -> ParentingProfileFactKind.Interest
            has(l.archiveRoutineWords) -> ParentingProfileFactKind.Routine
            has(l.archiveAgeWords) -> ParentingProfileFactKind.AgeStage
            has(l.archiveTraitWords) -> ParentingProfileFactKind.Trait
            has(l.archiveMemberWords) -> ParentingProfileFactKind.Member
            else -> ParentingProfileFactKind.Preference
        }
    }

    private const val EXTRA = "，。！？、；：,.!?;:\"'“”‘’（）()[]【】"

    private fun cleanBody(text: String): String {
        var body = text
        for (lead in NaturalLexicon.archiveLeads.sortedByDescending { it.length }) body = body.replace(lead, " ")
        body = NaturalLexicon.removingSiblingReferences(body)
        body = body.replace(Regex("\\s+"), " ")
        return body.trim { it.isWhitespace() || EXTRA.indexOf(it) >= 0 || Character.getType(it).let { t -> t == Character.OTHER_PUNCTUATION.toInt() } }
    }
}
