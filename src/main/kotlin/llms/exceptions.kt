@file:Suppress("CanBeParameter")

package llms

import io.ktor.http.*

class UnsupportedCapabilitiesException(val capabilities: List<ModelCapabilityResult>)
	: Exception("Missing capabilities: ${capabilities.filter { it.level == InferenceFlag.Required  }}")

class UnsupportedFeatureException(message: String) : Exception(message)

class APIRequestFailedException(val url: String, val status_code: HttpStatusCode?, val body: String?) :
	Exception("URL: $url, Request failed: $status_code, body: $body")

class RequestCanceledException() : Exception("request canceled")

class InsufficientTokenBudgetException(message: String?) : Exception(message)

class CompletionFailedException(reason: String) : Exception(reason)

class UnhandledResponseException(val response_body: String, cause: Exception) : Exception("Got unexpected response body: $response_body", cause)