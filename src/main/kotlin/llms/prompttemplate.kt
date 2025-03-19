package llms

import kotlinx.serialization.Serializable
import com.charleskorn.kaml.Yaml
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

private const val PROMPT_FOLDER = "prompts"

// we need raw strings because we don't pass stop sequences into a tokenizer anymore, they're evaluated
// against the actual model output
private object EscapedStringSerializer : KSerializer<String> {
	override val descriptor: SerialDescriptor =
		PrimitiveSerialDescriptor("EscapedString", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: String) {
		encoder.encodeString(value)
	}

	override fun deserialize(decoder: Decoder): String {
		val rawString = decoder.decodeString()

		return rawString
			.replace("\\r", "\r")
			.replace("\\n", "\n")
			.replace("\\t", "\t")
	}
}

@Serializable
data class PromptMeta(
//	val stop_sequences: List<String>? = null,
	val stop_sequences: List<@Serializable(with=EscapedStringSerializer::class) String>? = null,
	val logit_bias: Map<Int, Int>? = null,
	val max_tokens: Int? = null,
)

data class PromptData(
	val prompt: String,
	val meta: PromptMeta?,
	val prepend: String? = null,
	val system_prompt: String? = null,
)

interface PromptLoader {
	fun getPromptData(name: String): PromptData
}

private fun File.readIfExists(): String? {
	return if (this.exists()) {
		this.readText()
	} else {
		null
	}
}

object DefaultPromptLoader : PromptLoader {
	private val prompt_cache = mutableMapOf<String, PromptData>()

	override fun getPromptData(name: String): PromptData {
		return prompt_cache.getOrPut(name) {
			val prompt = File(PROMPT_FOLDER, "$name.txt").readText()

			val metafile = File(PROMPT_FOLDER, "$name.yaml")
			val meta = metafile.readIfExists()?.let {
				try {
					Yaml.default.decodeFromString(PromptMeta.serializer(), it)
				} catch (e: Exception) {
					throw IllegalArgumentException("Failed to parse metadata in file `$name.yaml`", e)
				}
			}

			val prepend = File(PROMPT_FOLDER, "$name.prepend.txt")
			val systempromptfile = File(PROMPT_FOLDER, "$name.system.txt")

			PromptData(prompt, meta, prepend.readIfExists(), systempromptfile.readIfExists())
		}
	}
}

object PromptManager {
	var loader: PromptLoader = DefaultPromptLoader
}

private fun replaceVariables(templateName: String, str: String, variables: Map<String, String>): String {
	var ret = str
	for ((key, value) in variables) {
		ret = ret.replace("\$$key", value)
	}

	val unmatchedRegex = Regex("""\$[^a-zA-Z0-9_-]+""")
	val unmatched = unmatchedRegex.findAll(ret).toList()

	if (unmatched.isNotEmpty()) {
		throw IllegalArgumentException(
			"Unmatched variables found in template `$templateName`: " +
					unmatched.joinToString(", ") { it.value }
		)
	}

	return ret
}

class PromptTemplateBuilder(private val name: String) {
	private val variables = mutableMapOf<String, String>()

	fun variable(key: String, value: String) {
		variables[key] = value
	}

	fun build(): PromptData {
		val meta = PromptManager.loader.getPromptData(name)

		return meta.copy(
			prompt=replaceVariables(name, meta.prompt, variables),
			prepend=meta.prepend?.let { replaceVariables(name, it, variables) }
		)
	}
}

fun promptTemplate(name: String, block: (PromptTemplateBuilder.() -> Unit)? = null): PromptData {
	val builder = PromptTemplateBuilder(name)
	if (block != null) builder.block()
	return builder.build()
}

private class StopSequenceBuffer(private val stopSequences: List<String>) {
	private val longestStopLen = stopSequences.maxOf { it.length }
	private val buffer = StringBuilder()

	/**
	 * returns a pair:
	 * - first: a safe completion text that can be emitted immediately
	 * - second: whether a stop sequence was found
	 */
	fun append(newText: String): Pair<String, Boolean> {
		buffer.append(newText)

		// look for any stop sequence in the buffer.
		val stopindex = stopSequences
			.mapNotNull { stop -> buffer.indexOf(stop).takeIf { it >= 0 } }
			.minOrNull()

		return when {
			// found a stop sequence: output text up to it, discard the rest
			stopindex != null -> {
				val result = buffer.substring(0, stopindex)
				buffer.clear()
				Pair(result, true)
			}

			// no stop found: emit all but the last `longestStopLen` characters, which might combine
			// with future tokens to form a stop sequence
			buffer.length > longestStopLen -> {
				val discardLength = buffer.length - longestStopLen
				val res = buffer.substring(0, discardLength)
				buffer.delete(0, discardLength)
				Pair(res, false)
			}
			else -> "" to false
		}
	}
}

private class TemplateStreamCancel : Exception()

fun ChatModelAPI.templateStream(name: String, block: (PromptTemplateBuilder.() -> Unit)? = null) =
	templateStream(promptTemplate(name, block))

fun ChatModelAPI.templateStream(data: PromptData): Flow<Completion> {
	val meta = data.meta
	var sent = false
	val stopBuffer = run {
		if(meta?.stop_sequences != null && meta.stop_sequences.isNotEmpty())
			StopSequenceBuffer(meta.stop_sequences)
		else
			null
	}

	return flow {
		try {
			this@templateStream.multiTurn(
				listOf(Message(data.prompt)),
				inferenceParams {
					if (meta == null)
						return@inferenceParams

					if (meta.logit_bias != null)
						logitBias(meta.logit_bias)

					// we do stop strings manually now, and so don't pass them to the model
//					if(meta.stop_sequences != null)
//						stopSequences(meta.stop_sequences)

					if (meta.max_tokens != null)
						maxTokens(meta.max_tokens)

					if(data.system_prompt != null)
						systemPrompt(data.system_prompt)
				}
			)
				.collect {
					val text = when(it) {
						is LogProbCompletion -> it.content
						is TextCompletion -> it.content
					}

					// VLLM emits an empty string with finish_reason: null while waiting for the completion to
					// begin. idk if that's a queue or what, but we don't want to emit empty strings
					if(text == "")
						return@collect

					// this is to ensure that if we're in debug mode any log messages from the `multiTurn`'s HTTP
					// requests will be printed before any output from the flow will
					// it also means if the HTTP request fails we will never emit data, which is desirable
					if (sent == false) {
						sent = true

						if (data.prepend != null)
							emit(TextCompletion(data.prepend))
					}

//					print((it as TextCompletion).content)
					if (stopBuffer == null)
						emit(it)
					else {
						val text = when (it) {
							is TextCompletion -> it.content
							// this will require reconstructing logprob objects and discarding logprobs for tokens that
							// occur after the stop sequence
							else -> TODO("stopBuffer is not supported for logprobs at present")
						}

						val (safeText, shouldStop) = stopBuffer.append(text)
						if (safeText.isNotEmpty())
							emit(TextCompletion(safeText))

						if (shouldStop)
							throw CancellationException()
					}
				}
		} catch(_: CancellationException) {}
	}
}

suspend fun ChatModelAPI.templateString(name: String, block: (PromptTemplateBuilder.() -> Unit)? = null) =
	templateStream(name, block).asString()

suspend fun Flow<Completion>.asString(): String {
	return this
		// debugging: print all tokens
//		.map {
//			when(it) {
//				is TextCompletion -> print(it.content)
//				else -> {}
//			}
//			it
//		}
		.toList().joinToString("") {
			when(it) {
				is TextCompletion -> it.content
				else -> throw NotImplementedError()
			}
		}
}