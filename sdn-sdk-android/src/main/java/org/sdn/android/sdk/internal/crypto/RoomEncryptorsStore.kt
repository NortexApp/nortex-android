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

package org.sdn.android.sdk.internal.crypto

import org.sdn.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.sdn.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_OLM
import org.sdn.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_RATCHET
import org.sdn.android.sdk.internal.crypto.algorithms.IMXEncrypting
import org.sdn.android.sdk.internal.crypto.algorithms.megolm.MXMegolmEncryptionFactory
import org.sdn.android.sdk.internal.crypto.algorithms.megolm.MXRatchetEncryptionFactory
import org.sdn.android.sdk.internal.crypto.algorithms.olm.MXOlmEncryptionFactory
import org.sdn.android.sdk.internal.crypto.store.IMXCryptoStore
import org.sdn.android.sdk.internal.session.SessionScope
import javax.inject.Inject

@SessionScope
internal class RoomEncryptorsStore @Inject constructor(
        private val cryptoStore: IMXCryptoStore,
        private val ratchetEncryptionFactory: MXRatchetEncryptionFactory,
        private val megolmEncryptionFactory: MXMegolmEncryptionFactory,
        private val olmEncryptionFactory: MXOlmEncryptionFactory,
) {

    // MXEncrypting instance for each room.
    private val roomEncryptors: MutableMap<String /* room id */, MutableMap<String /* algorithm */, IMXEncrypting>> = HashMap()

    fun put(roomId: String, algId: String, alg: IMXEncrypting) {
        synchronized(roomEncryptors) {
            roomEncryptors.getOrPut(roomId) { mutableMapOf() }.put(algId, alg)
        }
    }

    fun get(roomId: String, algId: String = MXCRYPTO_ALGORITHM_MEGOLM): IMXEncrypting? {
        return synchronized(roomEncryptors) {
            val cache = roomEncryptors[roomId]
            if (cache != null && cache.containsKey(algId)) {
                return@synchronized cache[algId]
            } else {
                val alg: IMXEncrypting? = when (algId) {
                    MXCRYPTO_ALGORITHM_RATCHET -> ratchetEncryptionFactory.create(roomId)
                    MXCRYPTO_ALGORITHM_MEGOLM -> megolmEncryptionFactory.create(roomId)
                    MXCRYPTO_ALGORITHM_OLM -> olmEncryptionFactory.create(roomId)
                    else -> null
                }
                alg?.let { roomEncryptors.getOrPut(roomId) { mutableMapOf() }.put(algId, it) }
                return@synchronized alg
            }
        }
    }
}
