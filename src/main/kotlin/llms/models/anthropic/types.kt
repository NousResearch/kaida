import kotlinx.serialization.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class CacheControl(val type: String)

val CACHE_EPHEMERAL = CacheControl("ephemeral")

@Serializable
sealed class AnthropicContentBlock {
	abstract val cache_control: CacheControl?
}

@Serializable
@SerialName("text")
data class AnthropicTextBlock(
	val text: String,
	override val cache_control: CacheControl? = null,
) : AnthropicContentBlock()

@Serializable
@SerialName("image")
class AnthropicImageBlock(
	val source: Source,
	override val cache_control: CacheControl? = null
) : AnthropicContentBlock() {
	@Serializable
	data class Source(
		val type: SourceType,
		/**
		 * MIME type
		 */
		val media_type: String,
		/**
		 * base64 encoded image data
		 */
		val data: String
	) {
		@Serializable
		enum class SourceType {
			@SerialName("base64")
			Base64
		}
	}
}

@OptIn(ExperimentalSerializationApi::class)
@Serializer(forClass = AnthropicBeta::class)
object AnthropicBetaSerializer : KSerializer<AnthropicBeta> {
	override fun serialize(encoder: Encoder, value: AnthropicBeta) {
		encoder.encodeString(value.key)
	}

	override fun deserialize(decoder: Decoder): AnthropicBeta {
		val key = decoder.decodeString()
		return AnthropicBeta.entries.find { it.key == key }
			?: throw IllegalArgumentException("Unknown key: $key")
	}
}

@Serializable(with = AnthropicBetaSerializer::class)
enum class AnthropicBeta(val key: String) {
	MessageBatches("message-batches-2024-09-24"),
	PromptCaching("prompt-caching-2024-07-31"),
	ComputerUse("computer-use-2024-10-22"),
}

@Serializable
data class AnthropicMessageRequest(
	val model: String,
	val messages: List<Message>,
	val max_tokens: Int,
	val temperature: Double? = null,
	val system: List<AnthropicContentBlock>? = null,
	val stop_sequences: List<String>? = null,
	val stream: Boolean,
	val metadata: Metadata? = null,
	val top_k: Int? = null,
	val top_p: Double? = null,

	val tools: List<Tool>? = null,
	val tool_choice: ToolChoice? = null,
) {
	@Serializable
	data class Message(
		val role: Role,
		val content: List<AnthropicContentBlock>
	) {
		@Serializable
		enum class Role {
			@SerialName("user")
			User,
			@SerialName("assistant")
			Assistant
		}
	}

	@Serializable
	data class Metadata(
		val user_id: String? = null
	)

	@Serializable
	data class Tool(
		val name: String,
		val description: String,
		val input_schema: InputSchema
	) {
		@Serializable
		data class InputSchema(
			val type: String,
			val properties: Map<String, Property>,
			val required: List<String>
		) {
			@Serializable
			data class Property(
				val type: String,
				val description: String? = null
			)
		}
	}

	@Serializable
	data class ToolChoice(
		val type: ToolChoiceType,
		val name: String? = null,
		val disable_parallel_tool_use: Boolean? = null
	) {
		@Serializable
		enum class ToolChoiceType {
			@SerialName("auto")
			Auto,
			@SerialName("any")
			Any,
			@SerialName("tool")
			Tool
		}
	}
}

@Serializable
@SerialName("message_start")
data class AnthropicMessageResponse(
//	val id: String,
	val type: String,
//	val role: AnthropicMessageRequest.Message.Role,
//	val content: List<AnthropicContentBlock>,
//	val model: String,
	val stop_reason: String? = null,
	val stop_sequence: String? = null,
	val usage: Usage? = null
) : AnthropicStreamingSSEEvent() {
	@Serializable
	data class Usage(
		val input_tokens: Int,
		val output_tokens: Int
	)
}

@Serializable
sealed class AnthropicStreamingSSEEvent {
	@Serializable
	@SerialName("content_block_start")
	data class ContentBlockStart(
		val index: Int,
		val content_block: ContentBlock
	) : AnthropicStreamingSSEEvent()

	@Serializable
	@SerialName("ping")
	object Ping : AnthropicStreamingSSEEvent()

	@Serializable
	@SerialName("content_block_delta")
	data class ContentBlockDelta(
		val index: Int,
		val delta: TextDelta
	) : AnthropicStreamingSSEEvent()

	@Serializable
	@SerialName("content_block_stop")
	data class ContentBlockStop(
		val index: Int
	) : AnthropicStreamingSSEEvent()

	@Serializable
	@SerialName("message_delta")
	data class MessageDelta(
		val delta: MessageDeltaDelta,
		val usage: Usage
	) : AnthropicStreamingSSEEvent()

	@Serializable
	@SerialName("message_stop")
	object MessageStop : AnthropicStreamingSSEEvent()
}

@Serializable
data class ContentBlock(
	val type: String,
	val text: String
)

@Serializable
data class TextDelta(
	val type: String,
	val text: String
)

@Serializable
data class MessageDeltaDelta(
	val stop_reason: String,
	val stop_sequence: String? = null
)

@Serializable
data class Usage(
	val output_tokens: Int
)