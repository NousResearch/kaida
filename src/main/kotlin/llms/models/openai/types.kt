import OpenAIMessageResponse.LogProbs
import OpenAIMessageResponse.Usage
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class OpenAIMessageRequest(
	val model: String,
	val messages: List<OpenAIMessage>,
	val store: Boolean? = false,
	val metadata: Metadata? = null,
	val frequency_penalty: Double? = 0.0,
	val logit_bias: Map<String, Int>? = null,
	val logprobs: Boolean? = false,
	val top_logprobs: Int? = null,
	val max_completion_tokens: Int? = null,
	val n: Int? = 1,
	val modalities: List<String>? = listOf("text"),
	val prediction: Prediction? = null,
//	val audio: Audio? = null,
	val presence_penalty: Double? = 0.0,
	val response_format: ResponseFormat? = null,
	val seed: Int? = null,
	val service_tier: String? = "auto",
	val stop: List<String>? = null,
	val temperature: Double? = 1.0,
	val top_p: Double? = 1.0,

	// TODO: not supported on official openai, but common on openai compatible endpoints
	val min_p: Double = 0.0,
	val top_k: Int = -1,
	val repetition_penalty: Double = 1.0,
	val min_tokens: Int?=null,

	val tools: List<Tool>? = null,
	val tool_choice: ToolChoice? = null,
	val parallel_tool_calls: Boolean? = true,
	val stream: Boolean = false,
	val user: String? = null
) {
	init {
		// TODO: refined types for InferenceControl? is that crazy?
		assert((stop?.size ?: 0) <= 4)
	}

	@OptIn(ExperimentalSerializationApi::class)
	@Serializable
	@JsonClassDiscriminator("role")
	sealed class OpenAIMessage()

	interface ChatTurn {
		/**
		 * An optional name for the participant. Provides the model information to
		 * differentiate between participants of the same role.
		 */
		val name: String?
	}

	@Serializable
	@SerialName("system")
	data class SystemMessage(
		val content: String,
		override val name: String? = null
	) : ChatTurn, OpenAIMessage()

	@Serializable
	@SerialName("user")
	data class UserMessage(
		val content: String,
		override val name: String? = null
	) : ChatTurn, OpenAIMessage()

	@Serializable
	@SerialName("assistant")
	data class AssistantMessage(
		val content: String?,
		val refusal: String? = null,
		override val name: String? = null,
//		val audio: Audio? = null,
		val tool_calls: List<ToolCall>? = null,
		val function_call: FunctionCall? = null
	) : ChatTurn, OpenAIMessage()

	@Serializable
	@SerialName("tool")
	data class ToolMessage(
		val content: String,
		val tool_call_id: String
	) : OpenAIMessage()

	@Serializable
	@SerialName("function")
	data class FunctionMessage(
		val content: String?,
		val name: String
	) : OpenAIMessage()

	@Serializable
	data class Metadata(
		val tags: Map<String, String>? = null
	)

	/**
	 * used when a lot of the LLM output is known ahead of time
	 * https://platform.openai.com/docs/guides/predicted-outputs
	 *
	 * TODO: if prediction is used a lot of other stuff can't be used, logprobs, etc
	 */
	@Serializable
	data class Prediction(
		val prediction: StaticContent? = null
	)

	@Serializable
	data class StaticContent(
		// currently always "content"
		val type: String,
		@Serializable(with = ContentSerializer::class)
		val content: Content
	)

	@Serializable
	sealed class Content {
		@Serializable
		data class TextContent(val text: String) : Content()

		@Serializable
		data class ContentParts(val parts: List<ContentPart>) : Content()
	}

	@Serializable
	data class ContentPart(
		val type: String,
		val text: String
	)

	object ContentSerializer : JsonContentPolymorphicSerializer<Content>(Content::class) {
		override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Content> {
			return if (element is JsonPrimitive && element.isString) {
				Content.TextContent.serializer()
			} else if (element is JsonArray) {
				Content.ContentParts.serializer()
			} else {
				throw SerializationException("Unknown content type")
			}
		}
	}

	/**
	 * force the model to respond with a particular JSON structure
	 */
	@Serializable
	sealed class ResponseFormat {
		@Serializable
		data class Text(
			val type: String = "text"
		) : ResponseFormat()

		@Serializable
		data class JsonObject(
			val type: String = "json_object"
		) : ResponseFormat()

		// if we do a lot of this we should make better types
		@Serializable
		data class JsonSchema(
			val type: String = "json_schema",
			val json_schema: Schema
		) : ResponseFormat() {
			@Serializable
			sealed class Schema {
				@Serializable
				data class DescriptionSchema(
					val name: String,
					val description: String? = null,
					val schema: SchemaNode,
					val strict: Boolean? = false
				) : Schema()

				@Serializable
				sealed class SchemaNode {
					/**
					 * "string", "number", "boolean", etc.
					 */
					data class Simple(
						val type: String,
					) : SchemaNode()

					/**
					 * objects, arrays
					 */
					data class Composite(
						val type: String = "object",
						val properties: Map<String, SchemaNode>,
						val required: List<String>? = null
					) : SchemaNode()
				}
			}
		}
	}

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
			Auto,
			None,
			Required,
			Function
		}
	}

	@Serializable
	data class ToolCall(
		val name: String,
		val arguments: JsonObject
	)

	@Serializable
	data class FunctionCall(
		val name: String,
		val arguments: JsonObject? = null
	)
}

@Serializable
data class OpenAIMessageResponse(
	val id: String,
	@SerialName("object")
	val object_: String,
	val created: Long,
	val model: String,
	val choices: List<Choice>,
	val usage: Usage
) {
	@Serializable
	data class Choice(
		val message: Message,
		val finish_reason: String? = null,
		val index: Int,
		val logprobs: LogProbs? = null
	) {
		@Serializable
		data class Message(
			val role: Role,
			val content: String
		) {
			@Serializable
			enum class Role {
				@SerialName("system")
				System,
				@SerialName("user")
				User,
				@SerialName("assistant")
				Assistant,
			}
		}
	}

	@Serializable
	data class LogProbEntry(
		val token: String,
		val logprob: Double,
		val bytes: List<UByte>? = null
	)

	@Serializable
	data class RootLogProb(
		val token: String,
		val logprob: Double,
		val bytes: List<UByte>? = null,
		val top_logprobs: List<LogProbEntry>
	)

	@Serializable
	data class LogProbs(
		val content: List<RootLogProb>? = null,
		val refusal: List<RootLogProb>? = null,
	)

	@Serializable
	data class Usage(
		val prompt_tokens: Int,
		val completion_tokens: Int,
		val total_tokens: Int
	)
}

@Serializable
data class OpenAIChunkedResponse(
	val id: String,
	@SerialName("object")
	val object_: String,
	val created: Long,
	val model: String,
	val choices: List<Choice>,
	/**
	 * An optional field that will only be present when you set
	 *
	 * stream_options: {"include_usage": true}
	 *
	 * in your request. When present, it contains a null value except for the last chunk
	 * which contains the token usage statistics for the entire request.
	 */
	val usage: Usage? = null
) {
	@Serializable
	data class Choice(
		val delta: Delta,
		val finish_reason: String? = null,
		val index: Int,
		val logprobs: LogProbs? = null
	) {
		@Serializable
		data class Delta(
			val role: OpenAIMessageResponse.Choice.Message.Role? = null,
			val content: String? = null,
			val refusal: String? = null
		)
	}
}