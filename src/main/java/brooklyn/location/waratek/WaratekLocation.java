/*
 * Copyright 2014 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.location.waratek;

import java.io.Closeable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.waratek.cloudvm.JavaVirtualMachine;
import brooklyn.entity.waratek.cloudvm.WaratekAttributes;
import brooklyn.entity.waratek.cloudvm.WaratekInfrastructure;
import brooklyn.location.MachineProvisioningLocation;
import brooklyn.location.NoMachinesAvailableException;
import brooklyn.location.basic.AbstractLocation;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.cloud.AvailabilityZoneExtension;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.stream.Streams;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;

public class WaratekLocation extends AbstractLocation implements WaratekVirtualLocation, MachineProvisioningLocation<WaratekMachineLocation> {

	private static final Logger LOG = LoggerFactory.getLogger(WaratekLocation.class);

    private Object lock;

    @SetFromFlag("provisioner")
    protected MachineProvisioningLocation<SshMachineLocation> provisioner;

    @SetFromFlag("infrastructure")
    protected WaratekInfrastructure infrastructure;

    protected final AtomicInteger obtainCounter = new AtomicInteger();

    public WaratekLocation() {
        this(Maps.newLinkedHashMap());
    }

    public WaratekLocation(Map properties) {
        super(properties);

        if (isLegacyConstruction()) {
            init();
        }
    }

    @Override
    public void init() {
        super.init();
        addExtension(AvailabilityZoneExtension.class, new WaratekContainerExtension(getManagementContext(), this));
    }

    @Override
    public String toString() {
        Object identity = getId();
        String configDescription = getLocalConfigBag().getDescription();
        if (configDescription!=null && configDescription.startsWith(getClass().getSimpleName()))
            return configDescription;
        return getClass().getSimpleName()+"["+getDisplayName()+":"+(identity != null ? identity : null)+
                (configDescription!=null ? "/"+configDescription : "") + "]";
    }

    @Override
    public String toVerboseString() {
        return Objects.toStringHelper(this).omitNullValues()
                .add("id", getId())
                .add("name", getDisplayName())
                .add("provisioner", provisioner)
                .add("infrastructure", infrastructure)
                .toString();
    }

    @Override
    public void configure(Map properties) {
        if (lock == null) {
            lock = new Object();
        }
        super.configure(properties);
    }

    @Override
    public void close() {
        if (provisioner instanceof Closeable) {
            Streams.closeQuietly((Closeable)provisioner);
        }
    }

    public WaratekMachineLocation obtain() throws NoMachinesAvailableException {
        return obtain(Maps.<String,Object>newLinkedHashMap());
    }

    @Override
    public WaratekMachineLocation obtain(Map<?,?> flags) throws NoMachinesAvailableException {
        // Question - what do we do when a *NON* java based application asks for a location
        // we _want_ to hand back a standard SshMachineLocation ...
        
        // 1. look through existing infrastructure for non-empty JVMs

        JavaVirtualMachine jvm = null;
        for (Entity entity : infrastructure.getJvmList()) {
            Integer maxSize = entity.getConfig(JavaVirtualMachine.JVC_CLUSTER_MAX_SIZE);
            Integer currentSize = entity.getAttribute(WaratekAttributes.JVC_COUNT);
            // also try to satisfy the affinty rules etc.
            if (currentSize == null || currentSize < maxSize) {
                jvm = (JavaVirtualMachine) entity;
                break;
            }
        }
        if (jvm == null) {
            // if not, get a new machine and deploy a JVM there
            SshMachineLocation machine = provisioner.obtain(flags);
            // increase size of JVM cluster
            // the new JVM entity will create a location
            // we need to get that location here somehow
            DynamicCluster cluster = infrastructure.getVirtualMachineCluster();
            Optional<Entity> added = cluster.growByOne(machine, flags);
            if (added.isPresent()) {
                jvm = (JavaVirtualMachine) added.get();
            }
        }
        WaratekMachineLocation location = jvm.getAttribute(JavaVirtualMachine.WARATEK_MACHINE_LOCATION);
        return location;
    }

    @Override
    public void release(WaratekMachineLocation machine) {
        if (provisioner != null) {
            provisioner.release(machine.getMachine());
        } else {
            throw new IllegalStateException("Request to release machine "+machine+", but this machine is not currently allocated");
        }
    }

    @Override
    public Map<String,Object> getProvisioningFlags(Collection<String> tags) {
        return Maps.<String,Object>newLinkedHashMap();
    }

    public List<Entity> getJvcList() {
        return infrastructure.getJvcList();
    }

    public List<Entity> getJvmList() {
        return infrastructure.getJvmList();
    }

    public WaratekInfrastructure getWaratekInfrastructure() {
        return infrastructure;
    }

    @Override
    public MachineProvisioningLocation<WaratekMachineLocation> newSubLocation(Map<?, ?> newFlags) {
        throw new UnsupportedOperationException();
    }

}
