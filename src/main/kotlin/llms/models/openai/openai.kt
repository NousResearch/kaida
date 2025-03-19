package llms.models.openai

import OpenAIChunkedResponse
import OpenAIMessageRequest
import OpenAIMessageRequest.*
import OpenAIMessageResponse.Choice.Message.Role
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import llms.*
import llms.configs.APIKey
import llms.configs.ModelWithCredentials
import llms.configs.OpenAIConfig

// far in the future to do: extract images if there are any, add them to the root images property
private fun Message.toOpenAIMessage(): OpenAIMessage {
	fun List<ContentBlock>.convertTurns(): String {
		return this.joinToString("\n") {
			when(it) {
				is TextBlock -> it.content
			}
		}
	}

	return when(this.role) {
		MessageRole.System -> SystemMessage(this.content.convertTurns())
		MessageRole.Assistant -> AssistantMessage(this.content.convertTurns())
		MessageRole.User -> UserMessage(this.content.convertTurns())
	}
}

private val role_lookup = mapOf(
	Role.System to MessageRole.System,
	Role.Assistant to MessageRole.Assistant,
	Role.User to MessageRole.User,
)

@Suppress("DuplicatedCode")
data class OpenAIAPI(val config: ModelWithCredentials<APIKey, OpenAIConfig>) : ChatModelAPI {
	override val capabilities = ModelCapabilities.build {
		systemPrompt()
		logProbs()
		logitBias()
		userId()
	}

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	private val client = HttpClient(CIO) {
		install(SSE)
		install(Logging) {
			level = config.model.http_log_level
		}
		install(HttpTimeout) {
			requestTimeoutMillis = 15_000
			connectTimeoutMillis = 5_000
			socketTimeoutMillis = 10_000
		}
		install(ContentNegotiation) {
			register(
				contentType = ContentType.Application.Json,
				converter = KotlinxSerializationConverter(json)
			)
		}
	}

	override suspend fun multiTurn(messages: List<Message>, params: InferenceParameters, ic: InferenceControl): Flow<Completion> {
		val payload = OpenAIMessageRequest(
			model = config.model.model.modelName(),
			messages =
				((params.systemPrompt?.run { listOf(Message(params.systemPrompt, MessageRole.System)) } ?: listOf())
					+ messages).map { it.toOpenAIMessage() },
			temperature = config.model.temperature,
			top_p = config.model.top_p,
			frequency_penalty = config.model.frequency_penalty,
			presence_penalty = config.model.presence_penalty,
			max_completion_tokens = params.maxTokens ?: config.model.max_tokens,
			service_tier = config.model.service_tier,
			logprobs = params.logProbs,
			top_logprobs = if(params.logProbs == true) 5 else null,
			stop = params.stopSequences,
			logit_bias = params.logitBias?.map { it.key.toString() to it.value }?.toMap(),

			min_p = config.model.min_p,
			top_k = config.model.top_k,
			repetition_penalty = config.model.repetition_penalty,

			stream = true,
			user = params.userId
		)

		val url = (config.auth.url?.toString() ?: "https://api.openai.com/v1").trim('/') + "/chat/completions"
		var role: MessageRole = MessageRole.Assistant

		return client.cancellableSSE(
			urlString=url,
			request={
				method = HttpMethod.Post
				contentType(ContentType.Application.Json)
				header(HttpHeaders.Authorization, "Bearer ${config.auth.api_key}")
				header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
				setBody(payload)
				expectSuccess=false
			}
		) { event ->
			val resp = try {
				json.decodeFromString<OpenAIChunkedResponse>(event.data!!)
			} catch(ex: SerializationException) {
				throw UnhandledResponseException(event.data!!, ex)
			}

			for (choice in resp.choices) {
				if(choice.finish_reason == "length")
					throw InsufficientTokenBudgetException("exceeded token budget")

				if(choice.delta.content == null)
					continue

				if(choice.delta.role != null) {
					role = role_lookup[choice.delta.role]!!
				}

				if(params.logProbs && choice.logprobs?.content != null) {
					val logprobs = choice.logprobs.content!!.map {
						LogProbCompletion.TokenChoices(
							LogProbCompletion.Token(it.token, it.logprob),
							it.top_logprobs.map {
								LogProbCompletion.Token(it.token, it.logprob)
							}
						)
					}

					send(LogProbCompletion(logprobs))
				} else {
					send(TextCompletion(choice.delta.content, role!!))
				}
			}
		}
	}
}