package io.github.nofuturekid.nova.data.alerts

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.exception.ApolloNetworkException
import io.github.nofuturekid.nova.data.api.ApolloClientFactory
import io.github.nofuturekid.nova.data.api.TlsTrust
import io.github.nofuturekid.nova.data.api.toArrayInfo
import io.github.nofuturekid.nova.data.api.toNotifications
import io.github.nofuturekid.nova.data.local.ApiKeyResult
import io.github.nofuturekid.nova.data.local.ApiKeyStore
import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.api.certIssue
import io.github.nofuturekid.nova.data.repository.RespClass
import io.github.nofuturekid.nova.data.repository.classifyResponse
import io.github.nofuturekid.nova.data.repository.failureMessage
import io.github.nofuturekid.nova.graphql.GetArrayQuery
import io.github.nofuturekid.nova.graphql.GetNotificationsQuery
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [ServerProbe]: the two read-only queries `GetNotifications` and `GetArray` that
 * the app already ships, sent one after the other to one server. No new document.
 *
 * What it does not do, on purpose, because nobody is looking at the phone:
 * - it never asks the user about a certificate and never stores a pin: a certificate that is
 *   not trusted or has changed is [ProbeResult.CertificateProblem], nothing more (ADR-0041,
 *   every client is built with `acceptFirstUse = false`);
 * - it never resets the key storage: a key that cannot be read is [ProbeResult.KeyMissing] or
 *   [ProbeResult.KeyUndecryptable] (ADR-0035);
 * - it does not use `UnraidRepository`'s streams, which raise the in-app certificate prompt.
 *
 * Addresses come from [checkAddresses]. An address that gives no answer (no connection, TLS
 * failure) is followed by the next one; an address whose server answers decides the result.
 * When no address answered, a certificate problem wins over "unreachable", so the user hears
 * that the certificate changed.
 */
@Singleton
class ApolloServerProbe internal constructor(
    private val readKey: suspend (serverId: String) -> ApiKeyResult,
    private val clientFor: (url: String, apiKey: String, trust: TlsTrust) -> ApolloClient,
) : ServerProbe {

    @Inject
    constructor(factory: ApolloClientFactory, keys: ApiKeyStore) : this(
        readKey = { keys.getResult(it) },
        clientFor = { url, key, trust -> factory.build(url, key, trust) },
    )

    override suspend fun probe(server: Server, pin: String?): ProbeResult {
        val key = when (val read = readKey(server.id)) {
            ApiKeyResult.Absent -> return ProbeResult.KeyMissing
            ApiKeyResult.Undecryptable -> return ProbeResult.KeyUndecryptable
            is ApiKeyResult.Present -> read.key.takeIf { it.isNotBlank() } ?: return ProbeResult.KeyMissing
        }
        var certificateProblem = false
        for (address in checkAddresses(server, pin)) {
            when (val outcome = probeAddress(clientFor(address.url, key, address.trust))) {
                is AddressOutcome.Answered -> return outcome.result
                AddressOutcome.CertificateProblem -> certificateProblem = true
                AddressOutcome.NoAnswer -> Unit
            }
        }
        return if (certificateProblem) ProbeResult.CertificateProblem else ProbeResult.Unreachable
    }

    private sealed interface AddressOutcome {
        data class Answered(val result: ProbeResult) : AddressOutcome
        data object CertificateProblem : AddressOutcome
        data object NoAnswer : AddressOutcome
    }

    private sealed interface Step<out T> {
        data class Loaded<T>(val value: T) : Step<T>
        /** The server answered, but with an error. */
        data class Failed(val message: String) : Step<Nothing>
        data object CertificateProblem : Step<Nothing>
        data object NoAnswer : Step<Nothing>
    }

    private suspend fun probeAddress(client: ApolloClient): AddressOutcome {
        val notifications = step(client, GetNotificationsQuery()) { it.toNotifications() }
        if (notifications is Step.CertificateProblem) return AddressOutcome.CertificateProblem
        if (notifications is Step.NoAnswer) return AddressOutcome.NoAnswer
        val array = step(client, GetArrayQuery()) { it.toArrayInfo() }
        if (array is Step.CertificateProblem) return AddressOutcome.CertificateProblem

        val loadedNotifications: Notifications? = (notifications as? Step.Loaded)?.value
        val loadedArray: ArrayInfo? = (array as? Step.Loaded)?.value
        if (loadedNotifications != null || loadedArray != null) {
            return AddressOutcome.Answered(ProbeResult.Ok(loadedNotifications, loadedArray))
        }
        if (array is Step.NoAnswer) return AddressOutcome.NoAnswer
        val message = (notifications as? Step.Failed)?.message ?: (array as? Step.Failed)?.message.orEmpty()
        return AddressOutcome.Answered(ProbeResult.Rejected(message))
    }

    private suspend fun <D : Query.Data, T> step(client: ApolloClient, query: Query<D>, map: (D) -> T): Step<T> = try {
        val response = client.query(query).execute()
        when (val c = classifyResponse(
            exception = response.exception,
            hasErrors = response.hasErrors(),
            errorMessage = response.errors?.joinToString { it.message },
            dataIsNull = response.data == null,
        )) {
            is RespClass.Cert -> Step.CertificateProblem
            // A transport failure says nothing about the server's view of us: try the next address.
            is RespClass.Failed -> if (response.exception is ApolloNetworkException) Step.NoAnswer else Step.Failed(c.message)
            RespClass.Empty -> Step.Failed("Empty GraphQL response")
            RespClass.Ok -> Step.Loaded(map(response.data!!))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // execute() reports transport problems in the response; this is the defensive path.
        when {
            e.certIssue() != null -> Step.CertificateProblem
            e is ApolloNetworkException -> Step.NoAnswer
            else -> Step.Failed(e.failureMessage("Unexpected error"))
        }
    }
}
