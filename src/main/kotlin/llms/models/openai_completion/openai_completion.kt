package llms.models.openai_completion

import OpenAICompletionRequest
import OpenAICompletionResponse
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import llms.*
import llms.configs.APIKey
import llms.configs.ModelWithCredentials
import llms.configs.OpenAICompletionConfig

data class OpenAICompletionAPI(val config: ModelWithCredentials<APIKey, OpenAICompletionConfig>) : ChatModelAPI {
	override val capabilities = ModelCapabilities.build {
		userId()
	}

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	private val client = HttpClient(CIO) {
		install (SSE)
		install(Logging) {
			level = config.model.http_log_level
		}
		install(HttpTimeout) {
			requestTimeoutMillis = 300_000
			connectTimeoutMillis = 300_000
			socketTimeoutMillis = 300_000
		}
		install(ContentNegotiation) {
			register(
				contentType = ContentType.Application.Json,
				converter = KotlinxSerializationConverter(json)
			)
		}
	}

	override suspend fun multiTurn(
		messages: List<Message>,
		params: InferenceParameters,
		ic: InferenceControl
	): Flow<Completion> {
		require(messages.size == 1) {"Completion API requires chat turn length of exactly one."}

		val payload = OpenAICompletionRequest(
			model = config.model.model,
			prompt = messages[0].content.joinToString("\n") {
				when (it) {
					is TextBlock -> it.content
				}
			},
			temperature = config.model.temperature,
			top_p = config.model.top_p,
			frequency_penalty = config.model.frequency_penalty,
			presence_penalty = config.model.presence_penalty,
			max_tokens = params.maxTokens ?: config.model.max_completion_length,
			logprobs = params.logProbs,
			stop = params.stopSequences,

			min_p = config.model.min_p,
			top_k = config.model.top_k,
			repetition_penalty = config.model.repetition_penalty,
			min_tokens=1,

			stream = true,
			user = params.userId
		)

		val url = (config.auth.url?.toString() ?: "https://api.openai.com/v1").trim('/') + "/completions"

		return client.cancellableSSE(
			urlString = url,
			request = {
				method = HttpMethod.Post
				contentType(ContentType.Application.Json)
				header(HttpHeaders.Authorization, "Bearer ${config.auth.api_key}")
				header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
				setBody(payload)
				expectSuccess=false
			}
		) { event ->
			val resp = try {
				json.decodeFromString<OpenAICompletionResponse>(event.data!!)
			} catch (ex: SerializationException) {
				throw UnhandledResponseException(event.data!!, ex)
			}

			for (choice in resp.choices) {
				if (choice.finish_reason == "length")
					throw InsufficientTokenBudgetException("exceeded token budget")

				if (params.logProbs) {
					throw NotImplementedError()
				} else {
					send(TextCompletion(choice.text, MessageRole.Assistant))
				}
			}
		}
	}
}