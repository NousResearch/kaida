package llms.configs

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import llms.ChatModelAPI
import llms.configs.YamlModelConfig.Companion.loadAuth
import llms.models.anthropic.AnthropicAPI
import llms.models.openai.OpenAIAPI
import llms.models.openai_completion.OpenAICompletionAPI
import java.io.File

internal val DEFAULT_HTTP_LOG_LEVEL = LogLevel.BODY

@Serializable
sealed class ModelConfig

@Serializable
sealed class AuthenticationProvider

@Serializable
data class ModelDefinition(val name: String, val auth: String, val model: ModelConfig)

@Serializable
data class AuthenticationDefinition(val name: String, val auth: AuthenticationProvider)

data class ModelWithCredentials<Auth : AuthenticationProvider, Model : ModelConfig>(val auth: Auth, val model: Model)

@Serializable
@SerialName("api_key")
class APIKey(
	val api_key: String,
	/**
	 * URL will implicitly default to the URL for the given model unless otherwise specified.
	 *
	 * As in: OpenAI will use the official OpenAI endpoint, but may also use an OpenAI compatible endpoint
	 * by overriding the URL here.
	 */
//	@Serializable(with=URLSerializer::class)
	val url: String? = null,
) : AuthenticationProvider()

interface ModelConfigProvider {
	fun getOrNull(model_name: String): ChatModelAPI?
	fun getAuthFor(model_name: String): AuthenticationDefinition
}

private inline fun <reified A : AuthenticationProvider, reified M : ModelConfig> safeCast(
	cfg: ModelWithCredentials<*, *>
): ModelWithCredentials<A, M> {
	if (cfg.auth !is A || cfg.model !is M)
		throw IllegalArgumentException(
			"Invalid types for ModelWithCredentials: " +
					"auth=${cfg.auth::class.simpleName}, model=${cfg.model::class.simpleName}. " +
					"Expected auth=${A::class.simpleName}, model=${M::class.simpleName}."
		)

	@Suppress("UNCHECKED_CAST")
	return cfg as ModelWithCredentials<A, M>
}

private val instantiators: Map<String, (ModelWithCredentials<*, *>) -> ChatModelAPI> = mapOf(
	AnthropicConfig::class.qualifiedName!! to { AnthropicAPI(safeCast(it)) },
	OpenAIConfig::class.qualifiedName!! to { OpenAIAPI(safeCast(it)) },
	OpenAICompletionConfig::class.qualifiedName!! to { OpenAICompletionAPI(safeCast(it)) }
)

private val yaml = Yaml(
	configuration = YamlConfiguration(polymorphismStyle=PolymorphismStyle.Property)
)

data class AuthenticatedModel(val auth: AuthenticationDefinition, val model: ChatModelAPI)

class YamlModelConfig(val models: Map<String, AuthenticatedModel>) : ModelConfigProvider {
	override fun getOrNull(model_name: String) = models[model_name]?.model
	override fun getAuthFor(model_name: String) = (models[model_name] ?: error("no model with name: ${model_name}")).auth

	companion object {
		fun loadAuth(auth_file: File) = yaml.decodeFromString<List<AuthenticationDefinition>>(auth_file.readText())

		fun loadFrom(auth_file: File, models_file: File): YamlModelConfig {
			return loadFrom(loadAuth(auth_file), models_file)
		}

		fun loadFrom(authentication: List<AuthenticationDefinition>, models_file: File): YamlModelConfig {
			val models: List<ModelDefinition> = yaml.decodeFromString(models_file.readText())

			return YamlModelConfig(
				models.associate { cfg ->
					val authProvider = authentication.firstOrNull { it.name == cfg.auth }
						?: throw IllegalArgumentException("Auth key '${cfg.auth}' not found")

					cfg.name to AuthenticatedModel(
						authProvider,
						instantiators[cfg.model::class.qualifiedName]!!(ModelWithCredentials(authProvider.auth, cfg.model)),
					)
				}
			)
		}
	}
}

private val CONFIG_FOLDER = File("config")
private val AUTH_CONFIG = File(CONFIG_FOLDER, "auth.yaml")

private val MODELS_CONFIG_FOLDER = File(CONFIG_FOLDER, "models")
private val MODELS_CONFIG = File(CONFIG_FOLDER, "models.yaml")

object ModelRegistry {
	private val auth = run {
		require(AUTH_CONFIG.exists()) { "Authentication file doesn't exist: $AUTH_CONFIG" }
		loadAuth(AUTH_CONFIG)
	}
	private var provider: ModelConfigProvider? = run {
		val paths = mutableListOf<File>().apply {
			if (MODELS_CONFIG.exists())
				add(MODELS_CONFIG)

			if (MODELS_CONFIG_FOLDER.exists())
				addAll(
					MODELS_CONFIG_FOLDER.walk()
						.filter { it.isFile && it.extension.equals("yaml", ignoreCase = true) }
						.toList()
				)
		}.toList()

		require(paths.isNotEmpty()) { "Failed to find any model configs in path: $CONFIG_FOLDER" }

		val merged = paths
			// load all the files
			.map { YamlModelConfig.loadFrom(auth, it).models }
			// flatten our List<Map<String, ChatModelAPI>> -> List<Map.Entry<String, ChatModelAPI>>
			.flatMap { it.entries }
			// group by key: Map<String, List<ChatModelAPI>>
			.groupBy({ it.key }, { it.value })
			.also { grouped ->
				// any keys that have more than one item had duplicate definitions
				val duplicates = grouped.filter { it.value.size > 1 }.keys

				require(duplicates.isEmpty()) { "Cannot have duplicate model keys: ${duplicates.joinToString(", ")}" }
			}
			// turn us back into Map<String, ChatModelAPI>
			.mapValues { it.value.single() }

		YamlModelConfig(merged)
	}


	fun get(model_name: String) = getOrNull(model_name)
		?: error("no model registered for model name: $model_name")
	fun getOrNull(model_name: String) = (provider ?: error("no ModelRegistry.provider set")).getOrNull(model_name)
	fun getAuth(model_name: String) = (provider ?: error("no ModelRegistry.provider set")).getAuthFor(model_name)

	fun setProvider(newProvider: ModelConfigProvider) {
		provider = newProvider
	}
}