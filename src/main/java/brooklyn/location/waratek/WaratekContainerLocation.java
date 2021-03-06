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

import groovy.lang.Closure;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractSoftwareProcessSshDriver;
import brooklyn.entity.waratek.cloudvm.JavaVirtualContainer;
import brooklyn.entity.waratek.cloudvm.JavaVirtualMachine;
import brooklyn.entity.waratek.cloudvm.WaratekAttributes;
import brooklyn.entity.waratek.cloudvm.WaratekInfrastructure;
import brooklyn.location.PortRange;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.dynamic.DynamicLocation;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.internal.ssh.SshTool;
import brooklyn.util.net.Protocol;
import brooklyn.util.os.Os;
import brooklyn.util.ssh.IptablesCommands;
import brooklyn.util.ssh.IptablesCommands.Chain;
import brooklyn.util.ssh.IptablesCommands.Policy;
import brooklyn.util.text.Strings;

import com.google.common.base.Joiner;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class WaratekContainerLocation extends SshMachineLocation implements DynamicLocation<JavaVirtualContainer, WaratekContainerLocation> {

    private static final Logger LOG = LoggerFactory.getLogger(WaratekContainerLocation.class);

    @SetFromFlag("machine")
    private SshMachineLocation machine;

    @SetFromFlag("owner")
    private JavaVirtualContainer jvc;

    @SetFromFlag("entity")
    private Entity entity;

    public WaratekContainerLocation() {
        this(Maps.newLinkedHashMap());
    }

    public WaratekContainerLocation(Map properties) {
        super(properties);

        if (isLegacyConstruction()) {
            init();
        }
    }

    @Override
    public void init() {
        super.init();
        setEntity(entity);
    }

    @Override
    public JavaVirtualContainer getOwner() {
        return jvc;
    }

    public void setEntity(Entity entity) {
        jvc.setRunningEntity(entity);
    }

    public Entity getEntity() {
        return jvc.getRunningEntity();
    }

    public JavaVirtualMachine getJavaVirtualMachine() {
        return jvc.getJavaVirtualMachine();
    }

    public List<String> injectWaratekCommands(List<String> commands) {
        List<String> updated  = Lists.newArrayList();
        if (getOwner().getJavaVirtualMachine().getConfig(JavaVirtualMachine.DEBUG)) {
            updated.add("set -x");
        }
        String javaHome = getJavaVirtualMachine().getJavaHome();
        String pathExport = String.format("export PATH=%s:$PATH", Os.mergePaths(javaHome, "bin"));
        updated.add(pathExport);
        updated.addAll(commands);

        if (LOG.isTraceEnabled()) {
            LOG.trace("Updated commands: {}", Joiner.on(" ; ").join(updated));
        }
        return updated;
    }

    public Map<String,?> injectWaratekEnvironment(Map<String,?> env) {
        List<String> opts = Lists.newArrayList();
        opts.add("--prefix=" + jvc.getJavaVirtualMachine().getRootDirectory());
        opts.add("--async");
        opts.add("--jvm=" + jvc.getJavaVirtualMachine().getJvmName());
        opts.add("--jvc=" + jvc.getJvcName());
        if (getOwner().getJavaVirtualMachine().getConfig(JavaVirtualMachine.DEBUG)) {
            opts.add("-verbose");
        }
        String waratekOpts = Joiner.on(" ").join(opts);

        String javaHome = getJavaVirtualMachine().getJavaHome();

        MutableMap<String, Object> updated = MutableMap.<String, Object>builder()
                .putAll(env)
                .put(JavaVirtualMachine.JAVA_HOME_VAR, javaHome)
                .build();

        // TODO make this configurable? we would need to know the entity being deployed here...
        // List of possible 'JAVA_OPTS' environment variables
        boolean modified = false;
        for (String var : ImmutableList.of("ACTIVEMQ_OPTS", "QPID_OPTS", "JAVA_OPTIONS", "JVM_OPTS", "KAFKA_JMX_OPTS", JavaVirtualMachine.JAVA_OPTS_VAR)) {
            if (env.containsKey(var)) {
                updated.put(var, waratekOpts + " " + env.get(var).toString());
                modified = true;
            }
        }
        // Create 'JAVA_OPTS' if it (or equivalent) doesn't exist
        if (!modified) {
            updated.put(JavaVirtualMachine.JAVA_OPTS_VAR, waratekOpts);
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("Updated environment: {}", Joiner.on(",").withKeyValueSeparator("=").join(updated));
        }
        return updated;
    }

    public Map<String,?> injectWaratekProps(Map<String,?> props) {
        MutableMap.Builder<String, Object> builder = MutableMap.<String, Object>builder().putAll(props);
        if (getJavaVirtualMachine().getConfig(JavaVirtualMachine.USE_WARATEK_USER)) {
            builder.put(SshTool.PROP_USER.getName(), getJavaVirtualMachine().getConfig(JavaVirtualMachine.WARATEK_USER));
        }
        Map<String, ?> updated = builder.build();

        if (LOG.isTraceEnabled()) {
            LOG.trace("Updated props: {}", Joiner.on(",").withKeyValueSeparator("=").join(updated));
        }
        return updated;
    }

    /*
     * Delegate port operations to machine. Note that firewall configuration is
     * fixed after initial provisioning, so updates use iptables to open ports.
     */

    private void addIptablesRule(Integer port) {
        if (getOwner().getJavaVirtualMachine().getInfrastructure().getConfig(WaratekInfrastructure.OPEN_IPTABLES)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Using iptables to add access for TCP/{} to {}", port, machine);
            }
            List<String> commands = ImmutableList.of(
                    IptablesCommands.insertIptablesRule(Chain.INPUT, Protocol.TCP, port, Policy.ACCEPT),
                    IptablesCommands.saveIptablesRules(),
                    IptablesCommands.listIptablesRule());
            int result = machine.execCommands(String.format("Open iptables TCP/%d", port), commands);
            if (result != 0) {
                String msg = String.format("Error running iptables update for TCP/{} on {}", port, machine);
                LOG.error(msg);
                throw new RuntimeException(msg);
            }
        }
    }

    @Override
    public boolean obtainSpecificPort(int portNumber) {
        boolean result = machine.obtainSpecificPort(portNumber);
        if (result) {
            addIptablesRule(portNumber);
        }
        return result;
    }

    @Override
    public int obtainPort(PortRange range) {
        int portNumber = machine.obtainPort(range);
        addIptablesRule(portNumber);
        return portNumber;
    }

    @Override
    public void releasePort(int portNumber) {
        machine.releasePort(portNumber);
    }

    @Override
    protected int execWithLogging(Map<String,?> props, String summaryForLogging, List<String> commands, Map env, final Closure<Integer> execCommand) {
        try {
            machine.acquireMutex("exec", "execWithLogging");
            if (LOG.isDebugEnabled()) {
                LOG.debug("Intercepted execWithLogging {}: {}", summaryForLogging, Strings.join(commands, ";"));
            }
            return super.execWithLogging(injectWaratekProps(props), summaryForLogging, injectWaratekCommands(commands), injectWaratekEnvironment(env), execCommand);
        } catch (InterruptedException ie) {
            throw Exceptions.propagate(ie);
        } finally {
            machine.releaseMutex("exec");
        }
    }

    @Override
    public int execScript(Map<String,?> props, String summaryForLogging, List<String> commands, Map<String,?> env) {
        try {
            machine.acquireMutex("exec", "execWithLogging");
            if (LOG.isDebugEnabled()) {
                LOG.debug("Intercepted execScript {}: {}", summaryForLogging, Strings.join(commands, ";"));
            }
            boolean ignoreResult = false;
            if (summaryForLogging != null) {
                // Handle check-running by retrieving JVC status directly
                if (summaryForLogging.startsWith(AbstractSoftwareProcessSshDriver.CHECK_RUNNING)) {
                    String status = jvc.getAttribute(WaratekAttributes.STATUS);
                    LOG.debug("Calculating check-running status based on: {}", status);
                    return JavaVirtualContainer.STATUS_SHUT_OFF.equals(status) ? 1 : 0;
                } else if (summaryForLogging.startsWith(AbstractSoftwareProcessSshDriver.INSTALLING)) {
                    jvc.shutDown();
                } else if (summaryForLogging.startsWith(AbstractSoftwareProcessSshDriver.STOPPING)) {
                    jvc.shutDown();
                    jvc.getDynamicLocation().setEntity(null);
                    ignoreResult = true;
                } else if (summaryForLogging.startsWith(AbstractSoftwareProcessSshDriver.KILLING)) {
                    jvc.stop();
                    ignoreResult = true;
                }
            }
            int result = super.execScript(injectWaratekProps(props), summaryForLogging, injectWaratekCommands(commands), injectWaratekEnvironment(env));
            return ignoreResult ? 0 : result;
        } catch (InterruptedException ie) {
            throw Exceptions.propagate(ie);
        } finally {
            machine.releaseMutex("exec");
        }
    }

    @Override
    public int execCommands(Map<String,?> props, String summaryForLogging, List<String> commands, Map<String,?> env) {
        try {
            machine.acquireMutex("exec", "execWithLogging");
            if (LOG.isDebugEnabled()) {
                LOG.debug("Intercepted execCommands {}: {}", summaryForLogging, Strings.join(commands, ";"));
            }
            return super.execCommands(injectWaratekProps(props), summaryForLogging, injectWaratekCommands(commands), injectWaratekEnvironment(env));
        } catch (InterruptedException ie) {
            throw Exceptions.propagate(ie);
        } finally {
            machine.releaseMutex("exec");
        }
    }

    @Override
    public void close() throws IOException {
        // TODO close down resources used by this container only
        LOG.info("Close called on JVC location (ignored): {}", this);
    }

    @Override
    public ToStringHelper string() {
        return super.string()
                .add("machine", machine)
                .add("jvc", jvc);
    }

}
