package llms

import kotlinx.serialization.Serializable

@Serializable
enum class Capability {
	/** return logprops in our completion */
	LogProbability,
	/** support for a top level system prompt, this is a subset of InlineSystemPrompt */
	SystemPrompt,

	/** whether in a multi-turn conversation a turn is allowed to be a mid-conversation system prompt */
	InlineSystemPrompt,
	StopSequences,
	LogitBias,
	SupportsUserId,
}

@Serializable
enum class InferenceFlag {
	Required,
	Preferred,
	Ignored;
}

@DslMarker
annotation class InferenceDSL

// TODO: tools
@Serializable
data class InferenceParameters(
	/**
	 * top level system prompt for the request. when [Capability.InlineSystemPrompt] is supported this will be
	 * transformed into a [Message] with [MessageRole.System] as the first message in the conversation.
	 */
	val systemPrompt: List<ContentBlock>? = null,
	/**
	 * by default this is required if present, however, InferenceFlag.Preferred may be used if stop sequences are
	 * only an optimization.
	 */
	val stopSequences: List<String>? = null,
	val logProbs: Boolean = false,
	val logitBias: Map<Int, Int>? = null,
	// TODO: check that maxTokens <= max possible tokens for model
	val maxTokens: Int? = null,
	/**
	 * provided only for abuse detection/tracking for those APIs which support it
	 *
	 * 	 see: https://platform.openai.com/docs/api-reference/batch/object#batch/object-metadata
	 * 	 see: metadata param https://docs.anthropic.com/en/api/messages
	 */
	val userId: String? = null,
) {
	/**
	 * converts this [InferenceParameters] to an [InferenceControl] by inferring which features are required.
	 *
	 * if some features should instead be [InferenceFlag.Preferred] this function accepts a receiver builder
	 * that may override them.
	 */
	fun asIC(block: (CapabilityMapBuilder<InferenceFlag>.() -> Unit)? = null): InferenceControl = InferenceControl.build {
		infer(this@InferenceParameters)

		if(block != null)
			block(this)
	}
}

@Serializable
data class InferenceControl(
	val capabilities: Map<Capability, InferenceFlag> = emptyMap()
) {
	fun getFlag(key: Capability): InferenceFlag = capabilities[key] ?: InferenceFlag.Ignored

	companion object {
		fun build(block: CapabilityMapBuilder<InferenceFlag>.() -> Unit): InferenceControl {
			val builder = CapabilityMapBuilder(InferenceFlag.Required)
			builder.block()
			return InferenceControl(builder.map())
		}
	}
}

/**
 * result class for [ModelCapabilities.checkFeatureSupport]
 */
data class ModelCapabilityResult(val key: Capability, val level: InferenceFlag, val support: Boolean)

@Serializable
data class ModelCapabilities(
	val capabilities: Map<Capability, Boolean> = emptyMap()
) {
	fun isSupported(key: Capability): Boolean = capabilities[key] ?: false

	fun checkFeatureSupport(ic: InferenceControl): List<ModelCapabilityResult> {
		return Capability.entries.map { key ->
			val level = ic.getFlag(key)
			val support = this.isSupported(key)
			ModelCapabilityResult(key, level, support)
		}
	}

	companion object {
		fun build(block: CapabilityMapBuilder<Boolean>.() -> Unit): ModelCapabilities {
			val builder = CapabilityMapBuilder(true)
			builder.block()
			return ModelCapabilities(builder.map())
		}
	}
}

// convenience type for better IDE support since enum keys are slightly unwieldy
@InferenceDSL
class CapabilityMapBuilder<T>(val default: T) {
	private val capabilities = mutableMapOf<Capability, T>()

	private fun set(key: Capability, value: T?) {
		capabilities[key] = value ?: default
	}

	fun infer(parameters: InferenceParameters) {
		parameters.apply {
			if (systemPrompt != null) this@CapabilityMapBuilder.systemPrompt()
			if (stopSequences != null) this@CapabilityMapBuilder.stopSequences()
			if (parameters.logProbs) this@CapabilityMapBuilder.logProbs()
			if (logitBias != null) this@CapabilityMapBuilder.logitBias()

			// userid is ignored since it's only present for observability
		}
	}

	fun all(value: T? = null) {
		Capability.entries.forEach {
			capabilities[it] = value ?: default
		}
	}
	fun logProbs(value: T? = null) = set(Capability.LogProbability, value)
	fun systemPrompt(value: T? = null) = set(Capability.SystemPrompt, value)
	fun inlineSystemPrompt(value: T? = null) = set(Capability.InlineSystemPrompt, value)
	fun stopSequences(value: T? = null) = set(Capability.StopSequences, value)
	fun logitBias(value: T? = null) = set(Capability.LogitBias, value)
	fun userId(value: T? = null) = set(Capability.SupportsUserId, value)

	fun map() = capabilities.toMap()
}