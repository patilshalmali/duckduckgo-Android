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
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.providerevents.ProviderEventsManager
import androidx.credentials.providerevents.exception.ImportCredentialsCancellationException
import androidx.credentials.providerevents.exception.ImportCredentialsInvalidJsonException
import androidx.credentials.providerevents.exception.ImportCredentialsNoExportOptionException
import androidx.credentials.providerevents.exception.ImportCredentialsUnknownErrorException
import androidx.credentials.providerevents.transfer.ImportCredentialsResponse
import androidx.credentials.providerevents.transfer.ProviderImportCredentialsResponse
import com.duckduckgo.autofill.api.domain.app.LoginCredentials
import com.duckduckgo.autofill.impl.importing.DomainNameNormalizer
import com.duckduckgo.autofill.impl.importing.ExistingCredentialMatchDetector
import com.duckduckgo.common.test.CoroutineTestRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RealCredentialTransferPasswordImporterTest {

    @get:Rule
    val coroutineTestRule: CoroutineTestRule = CoroutineTestRule()

    private val activity: Activity = mock()
    private val providerEventsManager: ProviderEventsManager = mock()
    private val providerEventsManagerFactory: ProviderEventsManagerFactory = mock {
        on { create(any()) } doReturn providerEventsManager
    }
    private val cxfPayloadParser: CxfPayloadParser = mock()
    private val domainNameNormalizer: DomainNameNormalizer = mock()
    private val existingCredentialMatchDetector: ExistingCredentialMatchDetector = mock()

    private val testee = RealCredentialTransferPasswordImporter(
        providerEventsManagerFactory = providerEventsManagerFactory,
        cxfPayloadParser = cxfPayloadParser,
        domainNameNormalizer = domainNameNormalizer,
        existingCredentialMatchDetector = existingCredentialMatchDetector,
        dispatchers = coroutineTestRule.testDispatcherProvider,
    )

    @Test
    fun whenExporterThrowsCancellationThenResultIsCancelled() = runTest {
        givenImportThrows(ImportCredentialsCancellationException())

        assertTrue(testee.importPasswords(activity) is CredentialTransferResult.Cancelled)
    }

    @Test
    fun whenExporterThrowsUnknownErrorWithNoMessageThenResultIsCancelled() = runTest {
        givenImportThrows(ImportCredentialsUnknownErrorException())

        assertTrue(testee.importPasswords(activity) is CredentialTransferResult.Cancelled)
    }

    @Test
    fun whenExporterThrowsUnknownErrorWithMessageThenResultIsUnknownFailure() = runTest {
        givenImportThrows(ImportCredentialsUnknownErrorException("something broke"))

        assertEquals(CredentialTransferFailure.UNKNOWN, failureReason())
    }

    @Test
    fun whenNoExporterAvailableThenResultSaysSo() = runTest {
        givenImportThrows(ImportCredentialsNoExportOptionException())

        assertEquals(CredentialTransferFailure.NO_EXPORTER_AVAILABLE, failureReason())
    }

    @Test
    fun whenExporterReturnsInvalidJsonThenResultIsMalformed() = runTest {
        givenImportThrows(ImportCredentialsInvalidJsonException("bad"))

        assertEquals(CredentialTransferFailure.MALFORMED_PAYLOAD, failureReason())
    }

    @Test
    fun whenPayloadCannotBeParsedThenResultIsMalformed() = runTest {
        givenImportReturns("{}")
        whenever(cxfPayloadParser.parse(any())) doReturn CxfParseResult.Malformed

        assertEquals(CredentialTransferFailure.MALFORMED_PAYLOAD, failureReason())
    }

    @Test
    fun whenCredentialsAlreadySavedThenOnlyNewOnesImportedAndOriginalCountPreserved() = runTest {
        val exported = listOf(credential("a"), credential("b"))
        givenImportReturns("{}")
        whenever(cxfPayloadParser.parse(any())) doReturn CxfParseResult.Parsed(exported)
        whenever(domainNameNormalizer.normalize(any())) doReturn "example.com"
        whenever(existingCredentialMatchDetector.filterExistingCredentials(any())) doReturn listOf(credential("a"))

        val result = testee.importPasswords(activity) as CredentialTransferResult.Success

        assertEquals(1, result.credentials.size)
        assertEquals(2, result.originalCount)
    }

    @Test
    fun whenPayloadRepeatsTheSameCredentialThenItIsOnlyOfferedOnce() = runTest {
        val duplicated = listOf(credential("a"), credential("a"))
        givenImportReturns("{}")
        whenever(cxfPayloadParser.parse(any())) doReturn CxfParseResult.Parsed(duplicated)
        whenever(domainNameNormalizer.normalize(any())) doReturn "example.com"
        whenever(existingCredentialMatchDetector.filterExistingCredentials(any())).thenAnswer { it.arguments[0] }

        val result = testee.importPasswords(activity) as CredentialTransferResult.Success

        assertEquals(1, result.credentials.size)
        assertEquals(2, result.originalCount)
    }

    private suspend fun failureReason() = (testee.importPasswords(activity) as CredentialTransferResult.Failure).reason

    private suspend fun givenImportThrows(exception: Throwable) {
        // thenThrow rejects these: the suspend signature declares no checked exceptions
        whenever(providerEventsManager.importCredentials(any(), any())).thenAnswer { throw exception }
    }

    private suspend fun givenImportReturns(json: String) {
        val response = ProviderImportCredentialsResponse(ImportCredentialsResponse(json), mock<CallingAppInfo>())
        whenever(providerEventsManager.importCredentials(any(), any())) doReturn response
    }

    private fun credential(username: String) = LoginCredentials(
        domain = "https://example.com/",
        username = username,
        password = "password",
    )
}
