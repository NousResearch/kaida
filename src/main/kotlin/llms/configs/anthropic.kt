package llms.configs

import io.ktor.client.plugins.logging.LogLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.collections.contains

// TODO: "extended thinking models" have 64k output length
// https://docs.anthropic.com/en/docs/about-claude/models/extended-thinking-models
private val modelCompletionLimits = mapOf(
	"claude-3-7-sonnet" to 8192,
	"claude-3-5-sonnet" to 8192,
	"claude-3-5-haiku" to 8192,
	"claude-3-opus" to 4096,
	"claude-3-sonnet" to 4096,
	"claude-3-haiku" to 4096,
	"claude-2.1" to 4096,
	"claude-2.0" to 4096,
	"claude-instant-1.2" to 4096
)

@Serializable
@SerialName("anthropic")
data class AnthropicConfig(
	val max_tokens: Int? = null,
	val model: String,
	val temperature: Double = 0.7,
	val top_k: Int = 50,
	val top_p: Double = 1.0,
	@Serializable(with=LogLevelSerializer::class)
	val http_log_level: LogLevel = DEFAULT_HTTP_LOG_LEVEL,
) : ModelConfig() {
	@Transient
	val max_tokens_calc = run {
		val model_limit = modelCompletionLimits.entries
			.firstOrNull { model.startsWith(it.key) }?.value

		if(model_limit != null) {
			if(max_tokens != null) {
				require(max_tokens in 1..model_limit) {
					"max_tokens for AnthropicConfig model $model must be between 1 and $model_limit"
				}
			}

			max_tokens ?: model_limit
		} else {
			// TODO: log a warning here that we don't recognize this model
			max_tokens ?: 4096
		}
	}

	init {
		require(temperature > 0 && temperature < 2) { "temperature must be 0 <= temperature <= 2" }
		require(top_p > 0 && top_p <= 1) { "top_p must be 0 <= top_p <= 1" }
	}
}