package llms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MessageRole {
	Assistant,
	System,
	User,
}

/*
TODO: improve ModelCapabilities to allow for subclassing so we can track our cache hit rate across api providers

e.g. anthropic's

Cache Limitations
The minimum cacheable prompt length is:

1024 tokens for Claude 3.5 Sonnet, Claude 3.5 Haiku, and Claude 3 Opus
2048 tokens for Claude 3 Haiku
Shorter prompts cannot be cached, even if marked with cache_control. Any requests to cache fewer than this
number of tokens will be processed without caching. To see if a prompt was cached, see the response usage fields.

The cache has a 5 minute time to live (TTL). Currently, “ephemeral” is the only supported cache type, which
corresponds to this 5-minute lifetime.
 */
@Serializable
enum class CacheHint {
	Cache,
	DoNotCache
}

/**
 * a single content block contained within a message.
 */
@Serializable
sealed class ContentBlock {
	abstract val cacheHint: CacheHint
}

@Serializable
data class TextBlock(
	val content: String,
	override val cacheHint: CacheHint = CacheHint.DoNotCache
) : ContentBlock()

/**
 * A single chat conversation turn, containing one or more content blocks. Content blocks may be a variety
 * of different types, such as:
 *
 * [TextBlock("...", CacheHint.Cache), ImageBlock(...), TextBlock("...", CacheHint.DoNotCache)]
 */
@Serializable
data class Message(
	val content: List<ContentBlock>,
	val role: MessageRole = MessageRole.User
) {
	constructor(content: String, role: MessageRole = MessageRole.User) : this(TextBlock(content), role)
	constructor(content: ContentBlock, role: MessageRole = MessageRole.User) : this(listOf(content), role)
}

// TODO: claude tool_use, tool_response, maybe images
@Serializable
sealed class Completion

interface ITextCompletion {
	val content: String
}

@Serializable
data class TextCompletion(
	override val content: String,
	val role: MessageRole = MessageRole.Assistant,
) : Completion(), ITextCompletion

@Serializable
data class LogProbCompletion(
	val tokens: List<TokenChoices>,
) : Completion(), ITextCompletion {
	@Serializable
	data class Token(val token: String, val logprob: Double)
	@Serializable
	data class TokenChoices(
		val selected: Token,
		@SerialName("options")
		private var _options: List<Token>
	) {
		val options: List<Token>
			get() = _options

		init {
			_options = _options.toSet().sortedByDescending { it.logprob }
		}
	}

	override val content = result()

	fun result() = tokens.joinToString("") { it.selected.token }
}