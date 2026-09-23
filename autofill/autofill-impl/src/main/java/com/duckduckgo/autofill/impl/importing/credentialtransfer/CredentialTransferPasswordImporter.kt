/*
 * Copyright (c) 2026 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.autofill.impl.importing.credentialtransfer

import android.app.Activity
import androidx.credentials.providerevents.exception.ImportCredentialsCancellationException
import androidx.credentials.providerevents.exception.ImportCredentialsInvalidJsonException
import androidx.credentials.providerevents.exception.ImportCredentialsNoExportOptionException
import androidx.credentials.providerevents.exception.ImportCredentialsUnknownErrorException
import androidx.credentials.providerevents.transfer.CredentialTypes
import androidx.credentials.providerevents.transfer.ImportCredentialsRequest
import com.duckduckgo.autofill.api.domain.app.LoginCredentials
import com.duckduckgo.autofill.impl.importing.DomainNameNormalizer
import com.duckduckgo.autofill.impl.importing.ExistingCredentialMatchDetector
import com.duckduckgo.autofill.impl.importing.credentialtransfer.CredentialTransferFailure.MALFORMED_PAYLOAD
import com.duckduckgo.autofill.impl.importing.credentialtransfer.CredentialTransferFailure.NO_EXPORTER_AVAILABLE
import com.duckduckgo.autofill.impl.importing.credentialtransfer.CredentialTransferFailure.UNKNOWN
import com.duckduckgo.autofill.impl.importing.credentialtransfer.CredentialTransferResult.Cancelled
import com.duckduckgo.autofill.impl.importing.credentialtransfer.CredentialTransferResult.Failure
import com.duckduckgo.autofill.impl.importing.credentialtransfer.CredentialTransferResult.Success
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.LogPriority.VERBOSE
import logcat.logcat
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Uses Android's Credential Transfer API (androidx.credentials:providerevents) to pull passwords
 * directly from another credential provider on the device, as an alternative to the existing
 * web-based Google Password Manager import flow.
 *
 * See https://developer.android.com/identity/sign-in/credential-transfer
 */
interface CredentialTransferPasswordImporter {
    /**
     * Must be called from the main thread: the API starts the system picker on the given activity.
     */
    suspend fun importPasswords(activity: Activity): CredentialTransferResult
}

sealed interface CredentialTransferResult {
    /**
     * [credentials] is what remains after de-duplication; [originalCount] is what the exporter sent,
     * so the result screen can report how many were skipped.
     */
    data class Success(
        val credentials: List<LoginCredentials>,
        val originalCount: Int,
    ) : CredentialTransferResult
    data object Cancelled : CredentialTransferResult
    data class Failure(val reason: CredentialTransferFailure) : CredentialTransferResult
}

/**
 * Bounded set of reasons, so the UI can map each to a translated message and telemetry can report
 * one without sending an unbounded string.
 */
enum class CredentialTransferFailure {
    NO_EXPORTER_AVAILABLE,
    MALFORMED_PAYLOAD,
    UNKNOWN,
}

@ContributesBinding(AppScope::class)
class RealCredentialTransferPasswordImporter @Inject constructor(
    private val providerEventsManagerFactory: ProviderEventsManagerFactory,
    private val cxfPayloadParser: CxfPayloadParser,
    private val domainNameNormalizer: DomainNameNormalizer,
    private val existingCredentialMatchDetector: ExistingCredentialMatchDetector,
    private val dispatchers: DispatcherProvider,
) : CredentialTransferPasswordImporter {

    override suspend fun importPasswords(activity: Activity): CredentialTransferResult {
        val request = ImportCredentialsRequest(
            credentialTypes = setOf(CredentialTypes.CREDENTIAL_TYPE_BASIC_AUTH),
            knownExtensions = emptySet(),
        )

        val cxfJson = try {
            val response = providerEventsManagerFactory.create(activity).importCredentials(activity, request)
            logcat(VERBOSE) { "${LOG_PREFIX}exporter ${response.callingAppInfo.packageName} returned a payload" }
            response.response.responseJson
        } catch (e: ImportCredentialsCancellationException) {
            logcat(VERBOSE) { "${LOG_PREFIX}import cancelled by user" }
            return Cancelled
        } catch (e: ImportCredentialsNoExportOptionException) {
            return Failure(NO_EXPORTER_AVAILABLE)
        } catch (e: ImportCredentialsInvalidJsonException) {
            return Failure(MALFORMED_PAYLOAD)
        } catch (e: ImportCredentialsUnknownErrorException) {
            // The Play Services backend never throws ImportCredentialsCancellationException. A user who
            // backs out of the system picker arrives here instead, with no message. Verified on device.
            return if (e.message == null) {
                logcat(VERBOSE) { "${LOG_PREFIX}import cancelled by user" }
                Cancelled
            } else {
                logcat(ERROR) { "${LOG_PREFIX}import failed: ${e.message}" }
                Failure(UNKNOWN)
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            logcat(ERROR) { "${LOG_PREFIX}import failed: ${e.javaClass.name}, message=${e.message}" }
            return Failure(UNKNOWN)
        }

        return when (val parsed = cxfPayloadParser.parse(cxfJson)) {
            is CxfParseResult.Parsed -> {
                val toImport = deduplicate(parsed.credentials)
                logcat(VERBOSE) { "${LOG_PREFIX}${parsed.credentials.size} logins exported, ${toImport.size} new" }
                Success(credentials = toImport, originalCount = parsed.credentials.size)
            }
            is CxfParseResult.Malformed -> Failure(MALFORMED_PAYLOAD)
        }
    }

    private suspend fun deduplicate(credentials: List<LoginCredentials>): List<LoginCredentials> = withContext(dispatchers.io()) {
        val normalised = credentials
            .distinct()
            .map { it.copy(domain = domainNameNormalizer.normalize(it.domain)) }
        existingCredentialMatchDetector.filterExistingCredentials(normalised)
    }
}
