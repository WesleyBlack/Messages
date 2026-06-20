@file:Suppress("MaxLineLength")

package org.fossify.messages.helpers

import org.fossify.messages.models.EmojiReaction
import org.fossify.messages.models.Message

data class ParsedEmojiReaction(
    val emoji: String,
    val originalMessage: String,
    val isRemoval: Boolean = false,
)

data class TapbackReaction(
    val emoji: String,
    val addedText: String,
    val removedText: String,
)

object EmojiReactionHelper {
    val tapbackReactions = listOf(
        TapbackReaction("❤️", "Loved", "Removed a heart from"),
        TapbackReaction("👍", "Liked", "Removed a like from"),
        TapbackReaction("👎", "Disliked", "Removed a dislike from"),
        TapbackReaction("😂", "Laughed at", "Removed a laugh from"),
        TapbackReaction("‼️", "Emphasized", "Removed an exclamation from"),
        TapbackReaction("❓", "Questioned", "Removed a question mark from"),
    )

    private val reactionPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?> = linkedMapOf(
        Regex(
            "(?s)^\u200a[^\u200b\u200a]*\u200b([^\u200b]*)\u200b[^\u200b\u200a]*\u200a(.*)\u200a[^\u200b\u200a]*\u200a\\Z"
        ) to { match ->
            ParsedEmojiReaction(match.groupValues[1], match.groupValues[2])
        }
    )

    private val removalPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?> = linkedMapOf(
        Regex(
            "(?s)^\u200a[^\u200c\u200a]*\u200c([^\u200c]*)\u200c[^\u200c\u200a]*\u200a(.*)\u200a[^\u200c\u200a]*\u200a\\Z"
        ) to { match ->
            ParsedEmojiReaction(match.groupValues[1], match.groupValues[2], isRemoval = true)
        }
    )

    init {
        tapbackReactions.forEach { reaction ->
            addAppleTapbackPattern(reaction)
        }

        reactionPatterns[Regex("""(?s)^Reacted (.+?) to ["“](.+?)["”]$""")] = { match ->
            if (match.groupValues.getOrNull(1) == "with a sticker") {
                null
            } else {
                ParsedEmojiReaction(match.groupValues[1], match.groupValues[2])
            }
        }
        removalPatterns[Regex("""(?s)^Removed (.+?) from ["“](.+?)["”]$""")] = { match ->
            ParsedEmojiReaction(match.groupValues[1], match.groupValues[2], isRemoval = true)
        }
    }

    fun buildTapbackMessage(message: Message, reaction: TapbackReaction, isRemoval: Boolean): String {
        val action = if (isRemoval) reaction.removedText else reaction.addedText
        return "$action “${message.body.trim()}”"
    }

    fun parseEmojiReaction(body: String): ParsedEmojiReaction? {
        parseRemoval(body)?.let { return it }

        return reactionPatterns.firstNotNullOfOrNull { (pattern, parser) ->
            pattern.find(body)?.let { match -> parser(match) }
        }
    }

    fun applyEmojiReactions(messages: List<Message>): ArrayList<Message> {
        messages.forEach { message ->
            message.isEmojiReaction = false
            message.emojiReactions = emptyList()
        }

        val orderedMessages = messages.sortedWith(compareBy<Message> { it.date }.thenBy { it.id })
        orderedMessages.forEach { reactionMessage ->
            val parsedReaction = parseEmojiReaction(reactionMessage.body) ?: return@forEach
            val targetMessage = findTargetMessage(
                messages = orderedMessages,
                reactionMessage = reactionMessage,
                originalMessageText = parsedReaction.originalMessage,
            ) ?: return@forEach

            if (parsedReaction.isRemoval) {
                removeEmojiReaction(reactionMessage, parsedReaction, targetMessage)
            } else {
                saveEmojiReaction(reactionMessage, parsedReaction, targetMessage)
            }
        }

        return orderedMessages
            .filterNot { it.isEmojiReaction }
            .toCollection(ArrayList())
    }

    private fun addAppleTapbackPattern(reaction: TapbackReaction) {
        reactionPatterns[Regex("""(?s)^${reaction.addedText} ["“](.+?)["”]$""")] = { match ->
            ParsedEmojiReaction(reaction.emoji, match.groupValues[1])
        }
        removalPatterns[Regex("""(?s)^${reaction.removedText} ["“](.+?)["”]$""")] = { match ->
            ParsedEmojiReaction(reaction.emoji, match.groupValues[1], isRemoval = true)
        }
    }

    private fun parseRemoval(body: String): ParsedEmojiReaction? {
        return removalPatterns.firstNotNullOfOrNull { (pattern, parser) ->
            pattern.find(body)?.let { match -> parser(match) }
        }
    }

    private fun findTargetMessage(
        messages: List<Message>,
        reactionMessage: Message,
        originalMessageText: String,
    ): Message? {
        val originalMessageRegex = parseTruncatedMessage(originalMessageText)
        return messages
            .asReversed()
            .firstOrNull { candidate ->
                candidate.threadId == reactionMessage.threadId &&
                    candidate.id != reactionMessage.id &&
                    candidate.date <= reactionMessage.date &&
                    !candidate.isEmojiReaction &&
                    originalMessageRegex.matches(candidate.body.trim())
            }
    }

    private fun parseTruncatedMessage(originalMessageText: String): Regex {
        val reactionText = originalMessageText.trim()
        val delimiter = "\u2026"
        val index = reactionText.lastIndexOf(delimiter)
        val regexPattern = if (index == -1) {
            Regex.escape(reactionText)
        } else {
            val before = reactionText.take(index)
            Regex.escape(before) + ".*"
        }
        return Regex("^$regexPattern$", RegexOption.DOT_MATCHES_ALL)
    }

    private fun saveEmojiReaction(
        reactionMessage: Message,
        parsedReaction: ParsedEmojiReaction,
        targetMessage: Message,
    ) {
        val reaction = EmojiReaction(
            reactionMessageId = reactionMessage.id,
            senderPhoneNumber = reactionMessage.senderPhoneNumber,
            emoji = parsedReaction.emoji,
            originalMessageText = parsedReaction.originalMessage,
            isMine = !reactionMessage.isReceivedMessage(),
        )
        targetMessage.emojiReactions = targetMessage.emojiReactions
            .filterNot { it.isMine == reaction.isMine && it.senderPhoneNumber == reaction.senderPhoneNumber } + reaction
        reactionMessage.isEmojiReaction = true
    }

    private fun removeEmojiReaction(
        reactionMessage: Message,
        parsedReaction: ParsedEmojiReaction,
        targetMessage: Message,
    ) {
        targetMessage.emojiReactions = targetMessage.emojiReactions.filterNot { reaction ->
            reaction.isMine == !reactionMessage.isReceivedMessage() &&
                reaction.senderPhoneNumber == reactionMessage.senderPhoneNumber &&
                reaction.emoji == parsedReaction.emoji
        }
        reactionMessage.isEmojiReaction = true
    }
}
