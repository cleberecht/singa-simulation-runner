package bio.singa.simulation.runner.cli;

import bio.singa.exchange.Converter;
import bio.singa.exchange.ProcessingTicket;
import bio.singa.exchange.SimulationRepresentation;
import bio.singa.features.quantities.MolarConcentration;
import bio.singa.simulation.model.simulation.Simulation;
import bio.singa.simulation.runner.managers.VariationManager;
import bio.singa.simulation.runner.converters.ConcentrationUnitConverter;
import bio.singa.simulation.runner.converters.TimeQuantityConverter;
import bio.singa.simulation.runner.converters.TimeUnitConverter;
import bio.singa.simulation.trajectories.Recorders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import tech.units.indriya.quantity.Quantities;

import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.quantity.Time;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.Callable;

import static bio.singa.features.units.UnitProvider.NANO_MOLE_PER_LITRE;
import static picocli.CommandLine.*;
import static tech.units.indriya.unit.Units.SECOND;

/**
 * @author cl
 */
@Command(description = "Generate tickets for parallel processing",
        name = "ticket-generator",
        version = "v0.0.1",
        mixinStandardHelpOptions = true)
public class TicketGenerator implements Callable<Void> {

    private static final Logger logger = LoggerFactory.getLogger(TicketGenerator.class);

    @Parameters(index = "0",
            description = "The json file with the simulation.")
    private Path simulationSetupPath;

    @Parameters(index = "1",
            description = "The target folder, where tickets are created\n(default: [current working directory])")
    private Path targetDirectory = Paths.get("");

    @Option(names = {"-t", "--termination-time"},
            description = "The termination time of the simulation\n(e.g.: 10s, 0.5min, 1.5h; default: : ${DEFAULT-VALUE})",
            converter = TimeQuantityConverter.class)
    private Quantity<Time> terminationTime = Quantities.getQuantity(1, SECOND);

    @Option(names = {"-n", "--observation-number"},
            description = {"The number of observations during simulation",
                    "default: ${DEFAULT-VALUE}"})
    private double observations = 100.0;

    @Option(names = {"-c", "--observed-concentration"},
            description = {"The unit in which concentrations are logged",
                    "e.g.: mol/L; default: ${DEFAULT-VALUE}"},
            converter = ConcentrationUnitConverter.class)

    private Unit<MolarConcentration> observedConcentrationUnit = NANO_MOLE_PER_LITRE;

    @Option(names = {"-i", "--observed-time"},
            description = {"The unit in which times are logged",
                    "e.g.: ms; default: ${DEFAULT-VALUE}"},
            converter = TimeUnitConverter.class)
    private Unit<Time> observedTimeUnit = SECOND;

    @Option(names = {"-l", "--limit"},
            description = {"Limits the number of tickets generated (for tests)."})
    private int maxTickets = -1;

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
        Path ticketPath = targetDirectory;
        Recorders.createDirectories(ticketPath);

        // generate status paths
        Path openPath = ticketPath.resolve("open");
        Recorders.createDirectories(openPath);
        Recorders.createDirectories(ticketPath.resolve("processing"));
        Recorders.createDirectories(ticketPath.resolve("done"));

        // create simulation (to cache entities etc)
        Simulation simulation = SimulationRepresentation.to(representation);
        VariationManager variationManager = new VariationManager();

        int i = 1;
        while (variationManager.hasVariationsLeft()) {
            // get next variation set
            variationManager.nextVariationSet();
            // create id
            String ticketId = UUID.randomUUID().toString();
            // create ticket
            ProcessingTicket ticket = variationManager.generateTicket(ticketId, simulationSetupPath.getFileName().toString(), terminationTime, terminationTime.divide(observations), observedConcentrationUnit, observedTimeUnit);
            // write ticket
            variationManager.writeTicket(ticket, openPath);
            if (maxTickets == -1) {
                continue;
            } else if (i >= maxTickets) {
                break;
            }
            i++;
        }
        return null;
    }
}

