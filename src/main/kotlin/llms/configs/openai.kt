package llms.configs

import com.charleskorn.kaml.YamlContentPolymorphicSerializer
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlin.text.startsWith

private val modelCompletionLimits = mapOf(
	"gpt-4o" to 16384,
	"gpt-4o-2024-11-20" to 16384,
	"gpt-4o-2024-08-06" to 16384,
	"gpt-4o-2024-05-13" to 4096,
	"chatgpt-4o" to 16384,
	"gpt-4o-mini" to 16384,
	"o1-preview" to 32768,
	"o1-mini" to 65536,
	"gpt-4-turbo" to 4096,
	"gpt-4-0125-preview" to 4096,
	"gpt-4-1106-preview" to 4096,
	"gpt-4" to 8192,
	"gpt-3.5-turbo" to 4096,
)

object OpenAIModelSerializer : YamlContentPolymorphicSerializer<OpenAIModel>(OpenAIModel::class) {
	override fun selectDeserializer(node: YamlNode): DeserializationStrategy<OpenAIModel> {
		return when (node) {
			is YamlScalar -> {
				object : DeserializationStrategy<OpenAIModel> {
					override val descriptor = buildClassSerialDescriptor("DefaultOpenAIModel")
					override fun deserialize(decoder: Decoder): OpenAIModel {
						return DefaultOpenAIModel(decoder.decodeString())
					}
				}
			}
			is YamlMap -> CustomOpenAIModel.serializer()
			else -> throw SerializationException("Expected scalar or mapping for OpenAIModel, got ${node::class}")
		}
	}
}

@Serializable(with=OpenAIModelSerializer::class)
sealed class OpenAIModel {
	abstract fun modelName(): String
}

@Serializable
class DefaultOpenAIModel(val name: String) : OpenAIModel() {
	override fun modelName(): String = name
}

@Serializable
class CustomOpenAIModel(val custom_model_name: String, val max_completion_length: Int) : OpenAIModel() {
	override fun modelName(): String = custom_model_name
}

@Serializable
@SerialName("openai")
data class OpenAIConfig(
	val model: OpenAIModel,
	val frequency_penalty: Double? = 0.0,
	val max_tokens: Int? = null,
	val presence_penalty: Double? = 0.0,
	val seed: Int? = null,
	val service_tier: String? = "auto",
	val temperature: Double = 1.0,
	val top_p: Double = 1.0,
	val min_p: Double = 0.0,
	val top_k: Int = -1,
	val repetition_penalty: Double = 1.0,
	@Serializable(with=LogLevelSerializer::class)
	val http_log_level: LogLevel = DEFAULT_HTTP_LOG_LEVEL,
) : ModelConfig() {
	init {
		val model_completion_limit: Int

		when(model) {
			is DefaultOpenAIModel -> {
				val model_completion_limit = run {
					val model_limit = modelCompletionLimits.entries
						.firstOrNull { model.modelName().startsWith(it.key) }?.value

					if(model_limit != null) {
						if(max_tokens != null) {
							require(max_tokens in 1..model_limit) {
								"max_tokens for OpenAI model $model must be between 1 and $model_limit"
							}
						}

						max_tokens ?: model_limit
					} else {
						// TODO: log a warning here that we don't recognize this model
						max_tokens ?: 4096
					}
				}
			}
			is CustomOpenAIModel -> {
				model_completion_limit = model.max_completion_length
			}
		}

		require(frequency_penalty == null || frequency_penalty in -2.0..2.0) { "frequency_penalty must be -2 <= frequency_penalty <= 2" }
		require(temperature in 0.0..2.0) { "temperature must be 0 <= temperature <= 2" }
		require(top_p in 0.0..1.0) { "top_p must be 0 <= top_p <= 1" }
	}
}