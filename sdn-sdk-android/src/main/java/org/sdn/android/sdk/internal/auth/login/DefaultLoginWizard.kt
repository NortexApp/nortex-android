/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.sdn.android.sdk.internal.auth.login

import android.util.Patterns
import org.sdn.android.sdk.api.auth.LoginType
import org.sdn.android.sdk.api.auth.login.LoginProfileInfo
import org.sdn.android.sdk.api.auth.login.LoginWizard
import org.sdn.android.sdk.api.auth.registration.RegisterThreePid
import org.sdn.android.sdk.api.session.Session
import org.sdn.android.sdk.api.util.JsonDict
import org.sdn.android.sdk.internal.auth.AuthAPI
import org.sdn.android.sdk.internal.auth.PendingSessionStore
import org.sdn.android.sdk.internal.auth.SessionCreator
import org.sdn.android.sdk.internal.auth.data.PasswordLoginParams
import org.sdn.android.sdk.internal.auth.data.ThreePidMedium
import org.sdn.android.sdk.internal.auth.data.TokenLoginParams
import org.sdn.android.sdk.internal.auth.db.PendingSessionData
import org.sdn.android.sdk.internal.auth.registration.AddThreePidRegistrationParams
import org.sdn.android.sdk.internal.auth.registration.RegisterAddThreePidTask
import org.sdn.android.sdk.internal.network.executeRequest
import org.sdn.android.sdk.internal.session.content.DefaultContentUrlResolver
import org.sdn.android.sdk.internal.session.contentscanner.DisabledContentScannerService

internal class DefaultLoginWizard(
        private val authAPI: AuthAPI,
        private val sessionCreator: SessionCreator,
        private val pendingSessionStore: PendingSessionStore
) : LoginWizard {

    private var pendingSessionData: PendingSessionData = pendingSessionStore.getPendingSessionData() ?: error("Pending session data should exist here")

    private val getProfileTask: GetProfileTask = DefaultGetProfileTask(
            authAPI,
            DefaultContentUrlResolver(pendingSessionData.edgeNodeConnectionConfig, DisabledContentScannerService())
    )

    override suspend fun getProfileInfo(matrixId: String): LoginProfileInfo {
        return getProfileTask.execute(GetProfileTask.Params(matrixId))
    }

    override suspend fun login(
            login: String,
            password: String,
            initialDeviceName: String,
            deviceId: String?
    ): Session {
        val loginParams = if (Patterns.EMAIL_ADDRESS.matcher(login).matches()) {
            PasswordLoginParams.thirdPartyIdentifier(
                    medium = ThreePidMedium.EMAIL,
                    address = login,
                    password = password,
                    deviceDisplayName = initialDeviceName,
                    deviceId = deviceId
            )
        } else {
            PasswordLoginParams.userIdentifier(
                    user = login,
                    password = password,
                    deviceDisplayName = initialDeviceName,
                    deviceId = deviceId
            )
        }
        val credentials = executeRequest(null) {
            authAPI.login(loginParams)
        }

        return sessionCreator.createSession(credentials, pendingSessionData.edgeNodeConnectionConfig, LoginType.PASSWORD)
    }

    /**
     * Ref: https://matrix.org/docs/spec/client_server/latest#handling-the-authentication-endpoint
     */
    override suspend fun loginWithToken(loginToken: String): Session {
        val loginParams = TokenLoginParams(
                token = loginToken
        )
        val credentials = executeRequest(null) {
            authAPI.login(loginParams)
        }

        return sessionCreator.createSession(credentials, pendingSessionData.edgeNodeConnectionConfig, LoginType.SSO)
    }

    override suspend fun loginCustom(data: JsonDict): Session {
        val credentials = executeRequest(null) {
            authAPI.login(data)
        }

        return sessionCreator.createSession(credentials, pendingSessionData.edgeNodeConnectionConfig, LoginType.CUSTOM)
    }

    override suspend fun resetPassword(email: String) {
        val param = RegisterAddThreePidTask.Params(
                RegisterThreePid.Email(email),
                pendingSessionData.clientSecret,
                pendingSessionData.sendAttempt
        )

        pendingSessionData = pendingSessionData.copy(sendAttempt = pendingSessionData.sendAttempt + 1)
                .also { pendingSessionStore.savePendingSessionData(it) }

        val result = executeRequest(null) {
            authAPI.resetPassword(AddThreePidRegistrationParams.from(param))
        }

        pendingSessionData = pendingSessionData.copy(resetPasswordData = ResetPasswordData(result))
                .also { pendingSessionStore.savePendingSessionData(it) }
    }

    override suspend fun resetPasswordMailConfirmed(newPassword: String, logoutAllDevices: Boolean) {
        val resetPasswordData = pendingSessionData.resetPasswordData ?: throw IllegalStateException("Developer error - Must call resetPassword first")
        val param = ResetPasswordMailConfirmed.create(
                pendingSessionData.clientSecret,
                resetPasswordData.addThreePidRegistrationResponse.sid,
                newPassword,
                logoutAllDevices
        )

        executeRequest(null) {
            authAPI.resetPasswordMailConfirmed(param)
        }

        // Set to null?
        // resetPasswordData = null
    }
}
