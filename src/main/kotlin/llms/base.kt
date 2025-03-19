package llms

import kotlinx.coroutines.flow.Flow

private fun List<ModelCapabilityResult>.unsupported() = this.filter { it.level == InferenceFlag.Required && !it.support }

interface ChatModelAPI {
	val capabilities: ModelCapabilities

	fun assertCapabilitiesPresent(ic: InferenceControl) {
		val present = capabilities.checkFeatureSupport(ic)
		val unsupported = present.unsupported()

		if(unsupported.isNotEmpty())
			throw UnsupportedCapabilitiesException(unsupported)
	}

	suspend fun multiTurn(
		messages: List<Message>,
		params: InferenceParameters =InferenceParameters(),
		ic: (CapabilityMapBuilder<InferenceFlag>.() -> Unit)? = null
	): Flow<Completion> =
		multiTurn(messages, params, params.asIC(ic))

	suspend fun multiTurn(messages: List<Message>, params: InferenceParameters, ic: InferenceControl): Flow<Completion>
}