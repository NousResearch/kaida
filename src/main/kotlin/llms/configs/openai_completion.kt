package llms.configs

import io.ktor.client.plugins.logging.LogLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("openai_completion")
data class OpenAICompletionConfig(
	val model: String,
	val frequency_penalty: Double? = 0.0,
	val max_completion_length: Int? = 26000,
	val presence_penalty: Double? = 0.0,
	val seed: Int? = null,
	val temperature: Double = 1.0,
	val top_p: Double = 1.0,
	val min_p: Double = 0.0,
	val top_k: Int = -1,
	val repetition_penalty: Double = 1.0,
	@Serializable(with=LogLevelSerializer::class)
	val http_log_level: LogLevel = DEFAULT_HTTP_LOG_LEVEL,
) : ModelConfig() {
	init {
		require(frequency_penalty == null || frequency_penalty in -2.0..2.0) { "frequency_penalty must be -2 <= frequency_penalty <= 2" }
		require(temperature in 0.0..2.0) { "temperature must be 0 <= temperature <= 2" }
		require(top_p in 0.0..1.0) { "top_p must be 0 <= top_p <= 1" }
	}
}