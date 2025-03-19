package llms

@DslMarker
annotation class InferenceDsl

@InferenceDsl
class InferenceParametersBuilder {
	private val systemprompt: MutableList<ContentBlock> = mutableListOf()
	private var stopsequences: MutableList<String>? = null
	private var logprobs: Boolean = false
	private var logit_bias: MutableMap<Int, Int>? = null
	private var user_id: String? = null
	private var max_tokens: Int? = null

	fun systemPrompt(prompt: String) {
		systemprompt.clear()
		systemprompt.add(TextBlock(prompt))
	}

	fun stopSequence(sequence: String) {
		if (stopsequences == null)
			stopsequences = mutableListOf()

		stopsequences?.add(sequence)
	}

	fun stopSequences(sequences: List<String>) {
		if (stopsequences == null)
			stopsequences = mutableListOf()

		stopsequences!!.addAll(sequences)
	}

	fun maxTokens(tokens: Int) {
		max_tokens = tokens
	}

	fun logProbs(value: Boolean = true) {
		logprobs = value
	}

	fun logitBias(map: Map<Int, Int>) {
		logit_bias = map.toMutableMap()
	}

	fun logitBias(key: Int, value: Int) {
		if (logit_bias == null) {
			logit_bias = mutableMapOf()
		}
		logit_bias!![key] = value
	}

	fun userId(value: String) {
		user_id = value
	}

	fun build(): InferenceParameters = InferenceParameters(
		systemPrompt=if (systemprompt.isEmpty()) null else systemprompt,
		stopSequences=stopsequences,
		logProbs=logprobs,
		logitBias=logit_bias,
		maxTokens=max_tokens,
		userId=user_id
	)
}

fun inferenceParams(block: InferenceParametersBuilder.() -> Unit): InferenceParameters =
	InferenceParametersBuilder().apply(block).build()

//@InferenceDsl
//class ContentBlockBuilder {
//	private var content: String = ""
//
//	// TODO
//	fun text(value: String) {
//		content = value
//	}
//
//	fun build(): ContentBlock = ContentBlock(content)
//}
