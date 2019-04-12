package bio.singa.simulation.runner;

import bio.singa.simulation.features.variation.VariationSet;
import bio.singa.simulation.model.simulation.Simulation;
import singa.bio.exchange.model.Converter;
import singa.bio.exchange.model.SimulationRepresentation;
import singa.bio.exchange.model.variation.VariationGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * @author cl
 */
public class GenerationTest {

    public static void main(String[] args) throws IOException {

        // get simulation file
        Path simulationSetupPath = Paths.get("/home/leberech/IdeaProjects/singa-simulation-runner/target/equilibration_variation_setup.json");
        String simulationDocument = String.join("", Files.readAllLines(simulationSetupPath));

        // convert json to simulation
        SimulationRepresentation representation = Converter.getRepresentationFrom(simulationDocument);
        Simulation simulation = SimulationRepresentation.to(representation);


        VariationSet variationSet = VariationGenerator.generateVariationSet(representation);
        List<List<?>> parameterVariations = variationSet.generateAllCombinations();
    }

}
