/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.controller;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.apache.kafka.common.Endpoint;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.InconsistentClusterIdException;
import org.apache.kafka.common.errors.StaleBrokerEpochException;
import org.apache.kafka.common.message.BrokerRegistrationRequestData;
import org.apache.kafka.common.metadata.BrokerRegistrationChangeRecord;
import org.apache.kafka.common.metadata.FenceBrokerRecord;
import org.apache.kafka.common.metadata.RegisterBrokerRecord;
import org.apache.kafka.common.metadata.RegisterBrokerRecord.BrokerEndpoint;
import org.apache.kafka.common.metadata.RegisterBrokerRecord.BrokerEndpointCollection;
import org.apache.kafka.common.metadata.UnfenceBrokerRecord;
import org.apache.kafka.common.metadata.UnregisterBrokerRecord;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.metadata.BrokerRegistration;
import org.apache.kafka.metadata.FinalizedControllerFeatures;
import org.apache.kafka.metadata.RecordTestUtils;
import org.apache.kafka.metadata.placement.ClusterDescriber;
import org.apache.kafka.metadata.placement.PlacementSpec;
import org.apache.kafka.metadata.placement.UsableBroker;
import org.apache.kafka.server.common.ApiMessageAndVersion;
import org.apache.kafka.server.common.MetadataVersion;
import org.apache.kafka.timeline.SnapshotRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.apache.kafka.server.common.MetadataVersion.IBP_3_3_IV2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@Timeout(value = 40)
public class ClusterControlManagerTest {
    @ParameterizedTest
    @EnumSource(value = MetadataVersion.class, names = {"IBP_3_0_IV0", "IBP_3_3_IV2"})
    public void testReplay(MetadataVersion metadataVersion) {
        MockTime time = new MockTime(0, 0, 0);

        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        ClusterControlManager clusterControl = new ClusterControlManager.Builder().
            setTime(time).
            setSnapshotRegistry(snapshotRegistry).
            setSessionTimeoutNs(1000).
            setControllerMetrics(new MockControllerMetrics()).
            build();
        clusterControl.activate();
        assertFalse(clusterControl.unfenced(0));

        RegisterBrokerRecord brokerRecord = new RegisterBrokerRecord().setBrokerEpoch(100).setBrokerId(1);
        brokerRecord.endPoints().add(new BrokerEndpoint().
            setSecurityProtocol(SecurityProtocol.PLAINTEXT.id).
            setPort((short) 9092).
            setName("PLAINTEXT").
            setHost("example.com"));
        clusterControl.replay(brokerRecord);
        clusterControl.checkBrokerEpoch(1, 100);
        assertThrows(StaleBrokerEpochException.class,
            () -> clusterControl.checkBrokerEpoch(1, 101));
        assertThrows(StaleBrokerEpochException.class,
            () -> clusterControl.checkBrokerEpoch(2, 100));
        assertFalse(clusterControl.unfenced(0));
        assertFalse(clusterControl.unfenced(1));

        if (metadataVersion.isLessThan(IBP_3_3_IV2)) {
            UnfenceBrokerRecord unfenceBrokerRecord =
                    new UnfenceBrokerRecord().setId(1).setEpoch(100);
            clusterControl.replay(unfenceBrokerRecord);
        } else {
            BrokerRegistrationChangeRecord changeRecord =
                    new BrokerRegistrationChangeRecord().setBrokerId(1).setBrokerEpoch(100).setFenced((byte) -1);
            clusterControl.replay(changeRecord);
        }
        assertFalse(clusterControl.unfenced(0));
        assertTrue(clusterControl.unfenced(1));

        if (metadataVersion.isLessThan(IBP_3_3_IV2)) {
            FenceBrokerRecord fenceBrokerRecord =
                    new FenceBrokerRecord().setId(1).setEpoch(100);
            clusterControl.replay(fenceBrokerRecord);
        } else {
            BrokerRegistrationChangeRecord changeRecord =
                    new BrokerRegistrationChangeRecord().setBrokerId(1).setBrokerEpoch(100).setFenced((byte) 1);
            clusterControl.replay(changeRecord);
        }
        assertFalse(clusterControl.unfenced(0));
        assertFalse(clusterControl.unfenced(1));
    }

    @Test
    public void testRegistrationWithIncorrectClusterId() throws Exception {
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        ClusterControlManager clusterControl = new ClusterControlManager.Builder().
            setClusterId("fPZv1VBsRFmnlRvmGcOW9w").
            setTime(new MockTime(0, 0, 0)).
            setSnapshotRegistry(snapshotRegistry).
            setSessionTimeoutNs(1000).
            setControllerMetrics(new MockControllerMetrics()).
            build();
        clusterControl.activate();
        assertThrows(InconsistentClusterIdException.class, () ->
            clusterControl.registerBroker(new BrokerRegistrationRequestData().
                    setClusterId("WIjw3grwRZmR2uOpdpVXbg").
                    setBrokerId(0).
                    setRack(null).
                    setIncarnationId(Uuid.fromString("0H4fUu1xQEKXFYwB1aBjhg")),
                123L,
                new FinalizedControllerFeatures(Collections.emptyMap(), 456L)));
    }

    @Test
    public void testUnregister() throws Exception {
        RegisterBrokerRecord brokerRecord = new RegisterBrokerRecord().
            setBrokerId(1).
            setBrokerEpoch(100).
            setIncarnationId(Uuid.fromString("fPZv1VBsRFmnlRvmGcOW9w")).
            setRack("arack");
        brokerRecord.endPoints().add(new BrokerEndpoint().
            setSecurityProtocol(SecurityProtocol.PLAINTEXT.id).
            setPort((short) 9092).
            setName("PLAINTEXT").
            setHost("example.com"));
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        ClusterControlManager clusterControl = new ClusterControlManager.Builder().
            setTime(new MockTime(0, 0, 0)).
            setSnapshotRegistry(snapshotRegistry).
            setSessionTimeoutNs(1000).
            setControllerMetrics(new MockControllerMetrics()).
            build();
        clusterControl.activate();
        clusterControl.replay(brokerRecord);
        assertEquals(new BrokerRegistration(1, 100,
            Uuid.fromString("fPZv1VBsRFmnlRvmGcOW9w"), Collections.singletonMap("PLAINTEXT",
            new Endpoint("PLAINTEXT", SecurityProtocol.PLAINTEXT, "example.com", 9092)),
            Collections.emptyMap(), Optional.of("arack"), true),
                clusterControl.brokerRegistrations().get(1));
        UnregisterBrokerRecord unregisterRecord = new UnregisterBrokerRecord().
            setBrokerId(1).
            setBrokerEpoch(100);
        clusterControl.replay(unregisterRecord);
        assertFalse(clusterControl.brokerRegistrations().containsKey(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 10})
    public void testPlaceReplicas(int numUsableBrokers) throws Exception {
        MockTime time = new MockTime(0, 0, 0);
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        ClusterControlManager clusterControl = new ClusterControlManager.Builder().
            setTime(time).
            setSnapshotRegistry(snapshotRegistry).
            setSessionTimeoutNs(1000).
            setControllerMetrics(new MockControllerMetrics()).
            build();
        clusterControl.activate();
        for (int i = 0; i < numUsableBrokers; i++) {
            RegisterBrokerRecord brokerRecord =
                new RegisterBrokerRecord().setBrokerEpoch(100).setBrokerId(i);
            brokerRecord.endPoints().add(new BrokerEndpoint().
                setSecurityProtocol(SecurityProtocol.PLAINTEXT.id).
                setPort((short) 9092).
                setName("PLAINTEXT").
                setHost("example.com"));
            clusterControl.replay(brokerRecord);
            UnfenceBrokerRecord unfenceRecord =
                new UnfenceBrokerRecord().setId(i).setEpoch(100);
            clusterControl.replay(unfenceRecord);
            clusterControl.heartbeatManager().touch(i, false, 0);
        }
        for (int i = 0; i < numUsableBrokers; i++) {
            assertTrue(clusterControl.unfenced(i),
                String.format("broker %d was not unfenced.", i));
        }
        for (int i = 0; i < 100; i++) {
            List<List<Integer>> results = clusterControl.replicaPlacer().place(
                new PlacementSpec(0,
                    1,
                    (short) 3),
                new ClusterDescriber() {
                    @Override
                    public Iterator<UsableBroker> usableBrokers() {
                        return clusterControl.usableBrokers();
                    }
                }
            );
            HashSet<Integer> seen = new HashSet<>();
            for (Integer result : results.get(0)) {
                assertTrue(result >= 0);
                assertTrue(result < numUsableBrokers);
                assertTrue(seen.add(result));
            }
        }
    }

    @Test
    public void testIterator() throws Exception {
        MockTime time = new MockTime(0, 0, 0);
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        ClusterControlManager clusterControl = new ClusterControlManager.Builder().
            setTime(time).
            setSnapshotRegistry(snapshotRegistry).
            setSessionTimeoutNs(1000).
            setControllerMetrics(new MockControllerMetrics()).
            build();
        clusterControl.activate();
        assertFalse(clusterControl.unfenced(0));
        for (int i = 0; i < 3; i++) {
            RegisterBrokerRecord brokerRecord = new RegisterBrokerRecord().
                setBrokerEpoch(100).setBrokerId(i).setRack(null);
            brokerRecord.endPoints().add(new BrokerEndpoint().
                setSecurityProtocol(SecurityProtocol.PLAINTEXT.id).
                setPort((short) 9092 + i).
                setName("PLAINTEXT").
                setHost("example.com"));
            clusterControl.replay(brokerRecord);
        }
        for (int i = 0; i < 2; i++) {
            UnfenceBrokerRecord unfenceBrokerRecord =
                new UnfenceBrokerRecord().setId(i).setEpoch(100);
            clusterControl.replay(unfenceBrokerRecord);
        }
        RecordTestUtils.assertBatchIteratorContains(Arrays.asList(
            Arrays.asList(new ApiMessageAndVersion(new RegisterBrokerRecord().
                setBrokerEpoch(100).setBrokerId(0).setRack(null).
                setEndPoints(new BrokerEndpointCollection(Collections.singleton(
                    new BrokerEndpoint().setSecurityProtocol(SecurityProtocol.PLAINTEXT.id).
                        setPort((short) 9092).
                        setName("PLAINTEXT").
                        setHost("example.com")).iterator())).
                setFenced(false), (short) 0)),
            Arrays.asList(new ApiMessageAndVersion(new RegisterBrokerRecord().
                setBrokerEpoch(100).setBrokerId(1).setRack(null).
                setEndPoints(new BrokerEndpointCollection(Collections.singleton(
                    new BrokerEndpoint().setSecurityProtocol(SecurityProtocol.PLAINTEXT.id).
                        setPort((short) 9093).
                        setName("PLAINTEXT").
                        setHost("example.com")).iterator())).
                setFenced(false), (short) 0)),
            Arrays.asList(new ApiMessageAndVersion(new RegisterBrokerRecord().
                setBrokerEpoch(100).setBrokerId(2).setRack(null).
                setEndPoints(new BrokerEndpointCollection(Collections.singleton(
                    new BrokerEndpoint().setSecurityProtocol(SecurityProtocol.PLAINTEXT.id).
                        setPort((short) 9094).
                        setName("PLAINTEXT").
                        setHost("example.com")).iterator())).
                setFenced(true), (short) 0))),
                clusterControl.iterator(Long.MAX_VALUE));
    }
}
