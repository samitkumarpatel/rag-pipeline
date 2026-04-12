package dev.samitkumar.ragpipeline;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

// Module structure verification is purely static analysis — no Spring context
// is started, so no Testcontainers are needed here.
class ModularityTests {

    static final ApplicationModules modules =
            ApplicationModules.of(RagPipelineApplication.class);

    @Test
    void verifyModuleStructure() {
        modules.verify();
    }

    @Test
    void generateModuleDocumentation() {
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
