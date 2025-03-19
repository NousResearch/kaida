package llms

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.sse.ClientSSESession

import io.ktor.client.plugins.sse.*
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration

suspend fun <T> HttpClient.cancellableSSE(
	urlString: String,
	request: HttpRequestBuilder.() -> Unit,
	reconnectionTime: Duration? = null,
	showCommentEvents: Boolean = false,
	showRetryEvents: Boolean = false,
	block: suspend ProducerScope<T>.(event: ServerSentEvent) -> Unit
) = callbackFlow<T> {
	val sseJob = launch {
		try {
			this@cancellableSSE.sse(urlString, request, reconnectionTime, showCommentEvents, showRetryEvents) {
				try {
					incoming.collect { event ->
						// the SSE specification doesn't specify whether any event fields are required, thus the ktor library
						// has set all the event fields to nullable. however, data is surely always present in practice
						// https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events
						if(event.data!! == "[DONE]") {
							close(null)
							return@collect
						}

						block(event)
					}
				} catch (ex: Throwable) {
					if (ex is CancellationException)
						throw ex
					else
						close(ex)
				}
			}
		} catch(ex: Throwable) {
			if (ex is CancellationException) {
				throw CancellationException()
			} else if(ex is SSEClientException) {
				close(APIRequestFailedException(
					urlString,
					ex.response?.status,
					ex.response?.readRawBytes()?.toString(Charsets.UTF_8))
				)
			} else {
				close(ex)
			}
		}
	}

	awaitClose {
		sseJob.cancel()
	}
}