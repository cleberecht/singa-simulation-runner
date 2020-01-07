package bio.singa.simulation.runner;

import bio.singa.exchange.Converter;
import bio.singa.exchange.SimulationRepresentation;
import bio.singa.simulation.model.simulation.Simulation;
import bio.singa.simulation.trajectories.Recorders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * @author cl
 */
@CommandLine.Command(description = "Generate tickets for parallel processing",
        name = "singa-tickets",
        version = "v0.0.1",
        mixinStandardHelpOptions = true,
        sortOptions = false)
public class TicketGenerator implements Callable<Void> {

    private static final Logger logger = LoggerFactory.getLogger(TicketGenerator.class);

    @CommandLine.Parameters(index = "0",
            description = "The json file with the simulation.")
    Path simulationSetupPath;

    @CommandLine.Option(names = {"-d", "-target-directory"},
            description = "The target folder, where tickets are created\n(default: [current working directory])",
            order = 1)
    Path targetDirectory = Paths.get("");

    public static void main(String[] args) {
        CommandLine.call(new TicketGenerator(), args);
    }

    @Override
    public Void call() {
        // get simulation file
        String simulationDocument;
        try {
            simulationDocument = String.join("", Files.readAllLines(simulationSetupPath));
        } catch (IOException e) {
            logger.error("unable to read simulation file {}", simulationSetupPath, e);
            return null;
        }

        // convert json to simulation
        SimulationRepresentation representation;
        try {
            representation = Converter.getRepresentationFrom(simulationDocument);
        } catch (IOException e) {
            logger.error("encountered invalid or incomplete simulation setup file {}", simulationSetupPath, e);
            return null;
        }

        // generate observation directory
        Path ticketPath = targetDirectory.resolve("tickets");
        Recorders.createDirectories(ticketPath);

        // generate status paths
        Path openPath = ticketPath.resolve("open");
        Recorders.createDirectories(openPath);
        Recorders.createDirectories(ticketPath.resolve("processing"));
        Recorders.createDirectories(ticketPath.resolve("done"));

        // create simulation (to cache entities etc)
        Simulation simulation = SimulationRepresentation.to(representation);
        VariationManager variationManager = new VariationManager();

        while (variationManager.hasVariationsLeft()) {
            // get next variation set
            variationManager.nextVariationSet();
            // create variation log
            String ticketId = UUID.randomUUID().toString();
            variationManager.generateJsonLog(openPath, ticketId);
        }

        return null;
    }
}

