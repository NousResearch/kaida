import kotlinx.serialization.*

@Serializable
data class OpenAICompletionRequest(
	val model: String,
	val prompt: String,
	val frequency_penalty: Double? = 0.0,
	val logit_bias: Map<String, Int>? = null,
	val logprobs: Boolean? = false,
	val max_tokens: Int? = null,
	val n: Int? = 1,
	val presence_penalty: Double? = 0.0,
	val seed: Int? = null,
	val stop: List<String>? = null,
	val temperature: Double? = 1.0,

	// TODO: not supported on official openai, but common on openai compatible endpoints
	val min_p: Double = 0.0,
	val top_k: Int = -1,
	val repetition_penalty: Double = 1.0,
	val min_tokens: Int?=null,

	val top_p: Double? = 1.0,
	val stream: Boolean,
	val user: String? = null,
) {
	init {
		assert((stop?.size ?: 0) <= 4)
		// TODO: completion's logprobs response type is completely different from the type for chat completions
		//   add support at some future date
		assert(logprobs == false)
	}
}

@Serializable
data class OpenAICompletionResponse(
	val id: String,
	@SerialName("object")
	val object_: String,
	val created: Long,
	val model: String,
	val choices: List<Choice>,
	val usage: Usage? = null
) {
	@Serializable
	data class Choice(
		val text: String,
		val finish_reason: String? = null,
		val index: Int,
//		val logprobs: LogProbs? = null
	)

	@Serializable
	data class Usage(
		val prompt_tokens: Int,
		val completion_tokens: Int,
		val total_tokens: Int
	)
}