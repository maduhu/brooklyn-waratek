package brooklyn.entity.waratek;

import brooklyn.entity.java.VanillaJavaAppSshDriver;
import brooklyn.entity.waratek.cloudvm.JavaVirtualContainer;
import brooklyn.entity.waratek.cloudvm.JavaVirtualMachine;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.waratek.WaratekContainerLocation;
import brooklyn.util.os.Os;

import com.google.common.base.Optional;

/**
 * The SSH implementation of the {@link WaratekJavaAppDriver}.
 */
public class WaratekJavaApplicationSshDriver extends VanillaJavaAppSshDriver implements WaratekJavaApplicationDriver {

    public WaratekJavaApplicationSshDriver(WaratekJavaApplicationImpl entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    private JavaVirtualMachine getJvm() {
        return getJvc().getJavaVirtualMachine();
    }

    private JavaVirtualContainer getJvc() {
        return ((WaratekContainerLocation) getMachine()).getJavaVirtualContainer();
    }

    @Override
    protected String getLogFileLocation() {
        return Os.mergePaths(getJvm().getRootDirectory(), "var/log/javad", getJvm().getJvmName(), getJvc().getJvcName(), "console.log");
    }

    @Override
    protected Optional<String> getCurrentJavaVersion() {
        return Optional.of("1.6.0");
    }

    /** Does nothing; we are using the Waratek JVM instead. */
    @Override
    public final boolean installJava() { return true; }

    @Override
    public String getHeapSize() {
        Long heapSize = getEntity().getConfig(WaratekJavaApplication.MAX_HEAP_SIZE, 512 * (1024L * 1024L));
        int megabytes = (int) (heapSize / (1024L * 1024L));
        return megabytes + "m";
    }

    @Override
    public String getRootDirectory() {
        return getRunDir();
    }

}
