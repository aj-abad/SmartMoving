package choco.ratel.smartmoving.windrunner;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Starting point for client game tests, run by {@code ./gradlew runWindrunnerClientTest}. Fabric's runner
 * refuses to start without at least one entrypoint, so this stays even while it tests nothing; add
 * further test classes to the {@code fabric-client-gametest} list in this source set's fabric.mod.json.
 */
public final class WindrunnerClientTests implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {}
}
