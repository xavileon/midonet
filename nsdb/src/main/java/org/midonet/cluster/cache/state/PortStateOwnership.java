/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.cache.state;

import java.util.UUID;

import com.google.protobuf.Message;

import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Topology;

/**
 * Implements the state ownership interface for a port.
 */
public final class PortStateOwnership implements StateOwnership {

    /**
     * {@inheritDoc}
     */
    @Override
    public UUID ownerOf(UUID id, Message message) {
        if (message == null) {
            return null;
        }

        Topology.Port port = (Topology.Port) message;
        return port.hasHostId() ? new UUID(port.getHostId().getMsb(),
                                           port.getHostId().getLsb()) : null;
    }
}
