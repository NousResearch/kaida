package llms.models.anthropic

import AnthropicBeta
import AnthropicContentBlock
import AnthropicMessageRequest
import AnthropicMessageResponse
import AnthropicStreamingSSEEvent
import AnthropicTextBlock
import CACHE_EPHEMERAL
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import llms.*
import llms.configs.APIKey
import llms.configs.AnthropicConfig
import llms.configs.ModelWithCredentials

private fun ContentBlock.convertBlock(): AnthropicContentBlock {
	return when(this) {
		is TextBlock -> AnthropicTextBlock(
			this.content,
			if(this.cacheHint == CacheHint.Cache) CACHE_EPHEMERAL else null
		)
	}
}

data class AnthropicAPI(val config: ModelWithCredentials<APIKey, AnthropicConfig>) : ChatModelAPI {
	override val capabilities = ModelCapabilities.build {
		systemPrompt()
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
			requestTimeoutMillis = 60_000
			connectTimeoutMillis = 60_000
			socketTimeoutMillis = 60_000
		}
		install(ContentNegotiation) {
			register(
				contentType = ContentType.Application.Json,
				converter = KotlinxSerializationConverter(json)
			)
		}
	}

	private fun convertRole(role: MessageRole): AnthropicMessageRequest.Message.Role {
		return when(role) {
			MessageRole.User -> AnthropicMessageRequest.Message.Role.User
			MessageRole.Assistant -> AnthropicMessageRequest.Message.Role.Assistant
			MessageRole.System -> throw UnsupportedCapabilitiesException(
				listOf(
					ModelCapabilityResult(
						key = Capability.InlineSystemPrompt,
						level = InferenceFlag.Required,
						support = false
					)
				)
			)
		}
	}

	override suspend fun multiTurn(messages: List<Message>, params: InferenceParameters, ic: InferenceControl): Flow<Completion> {
		this.assertCapabilitiesPresent(ic)

		val payload = AnthropicMessageRequest(
			model = config.model.model,
			max_tokens = params.maxTokens ?: config.model.max_tokens_calc,
			temperature = config.model.temperature,
			system = params.systemPrompt?.run { this.map { it.convertBlock() } },
			stop_sequences = params.stopSequences,
			stream = true,
			metadata = params.userId?.run { AnthropicMessageRequest.Metadata(user_id=params.userId) },
			top_k = config.model.top_k,
			top_p = config.model.top_p,
//			tools = TODO(),
//			toolChoice = TODO(),

			messages = messages.map { msg ->
				AnthropicMessageRequest.Message(
					convertRole(msg.role),
					content = msg.content.map { it.convertBlock() },
				)
			},
		)

		val url = config.auth.url?.toString() ?: "https://api.anthropic.com/v1/messages"
		val betas = setOf(AnthropicBeta.PromptCaching)

		return client.cancellableSSE(
			urlString=url,
			request={
				method = HttpMethod.Post
				contentType(ContentType.Application.Json)
				header("x-api-key", config.auth.api_key)
				header("anthropic-beta", betas.joinToString(", ") {it.key})
				header("anthropic-version", "2023-06-01")
				setBody(payload)
				expectSuccess=false
			}
		) { event ->
			val resp = try {
				json.decodeFromString<AnthropicStreamingSSEEvent>(event.data!!)
			} catch(ex: SerializationException) {
				throw UnhandledResponseException(event.data!!, ex)
			}

			// TODO: anthropic can send multiple blocks concurrently, in principle, like e.g. a think block
			//       and a content block
			when(resp) {
				is AnthropicMessageResponse -> {}
				is AnthropicStreamingSSEEvent.ContentBlockStart -> send(TextCompletion(resp.content_block.text))
				is AnthropicStreamingSSEEvent.ContentBlockDelta -> send(TextCompletion(resp.delta.text))
				is AnthropicStreamingSSEEvent.ContentBlockStop -> {}
				is AnthropicStreamingSSEEvent.MessageDelta -> {
					if(resp.delta.stop_reason == "max_tokens")
						throw InsufficientTokenBudgetException("exceeded token budget")
				}
				AnthropicStreamingSSEEvent.MessageStop -> {
					close(null)
					return@cancellableSSE
				}
				AnthropicStreamingSSEEvent.Ping -> {}
			}
		}
	}
}