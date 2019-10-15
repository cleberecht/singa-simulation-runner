package bio.singa.simulation.runner;

import bio.singa.exchange.Converter;
import bio.singa.exchange.SimulationRepresentation;
import bio.singa.exchange.trajectories.TrajectoryDataset;
import bio.singa.features.quantities.MolarConcentration;
import bio.singa.simulation.model.agents.surfacelike.MembraneFactory;
import bio.singa.simulation.model.simulation.Simulation;
import bio.singa.simulation.model.simulation.SimulationManager;
import bio.singa.simulation.trajectories.Recorders;
import bio.singa.simulation.trajectories.nested.NestedUpdateRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import tech.units.indriya.quantity.Quantities;

import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.quantity.Time;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static bio.singa.features.units.UnitProvider.NANO_MOLE_PER_LITRE;
import static picocli.CommandLine.*;
import static tech.units.indriya.unit.MetricPrefix.MILLI;
import static tech.units.indriya.unit.Units.SECOND;

/**
 * @author cl
 */
@Command(description = "Execute singa simulations from json files.",
        name = "singa-run",
        version = "v0.0.1",
        mixinStandardHelpOptions = true,
        sortOptions = false)
public class SimulationRunner implements Callable<Void> {

    private static final Logger logger = LoggerFactory.getLogger(SimulationRunner.class);

    @Parameters(index = "0",
            description = "The json file with the simulation.")
    private Path simulationSetupPath;

    @Option(names = {"-t", "--termination-time"},
            description = "The termination time of the simulation\n(e.g.: 10s, 0.5min, 1.5h; default: 1min)",
            converter = TimeQuantityConverter.class,
            order = 0)
    private Quantity<Time> terminationTime = Quantities.getQuantity(10, MILLI(SECOND));

    @Option(names = {"-d", "-target-directory"},
            description = "The target folder, where observation files are created\n(default: [current working directory])",
            order = 1)
    private Path targetDirectory = Paths.get("");

    @Option(names = {"-oi", "--observation-number"},
            description = {"The number of observations during simulation",
                    "default: 100"},
            order = 3)
    private double observations = 10.0;

    @Option(names = {"-oc", "--observed-concentration"},
            description = {"The unit in which concentrations are logged",
                    "e.g.: mol/L; default: ${DEFAULT-VALUE}"},
            converter = ConcentrationUnitConverter.class,
            order = 4)
    private Unit<MolarConcentration> observedConcentrationUnit = NANO_MOLE_PER_LITRE;

    @Option(names = {"-ot", "--observed-time"},
            description = {"The unit in which times are logged",
                    "e.g.: ms; default: ${DEFAULT-VALUE}"},
            converter = TimeUnitConverter.class,
            order = 5)
    private Unit<Time> observedTimeUnit = SECOND;

    @Option(names = {"-v", "--variations"},
            description = {"Run the simulation with all combinations of alternate values annotated."},
            order = 6)
    private boolean variationRun = true;

    public static void main(String[] args) {
        CommandLine.call(new SimulationRunner(), args);
    }

    @Override
    public Void call() {

        // TODO make variation simulation resumeable (similar to runner in phd-simulations)

        System.out.println();
        System.out.println("Preparing simulation");
        System.out.println();

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
        Path observationDirectory = targetDirectory;//.resolve("observations");
        Recorders.createDirectories(observationDirectory);

        // variation simulation
        if (variationRun) {
            // create simulation (to cache entities etc)
            Simulation simulation = SimulationRepresentation.to(representation);
            VariationManager variationManager = new VariationManager();
            // TODO add "big" progressbar for global simulation progress
            // for each variation set
            while (variationManager.hasVariationsLeft()) {
                System.out.println("applying variation for simulation " + (variationManager.getCurrentVariation()+1) + " of " + variationManager.getPossibleVariations());
                // create simulation
                simulation = SimulationRepresentation.to(representation);
                simulation.getScheduler().setRecalculationCutoff(0.05);
                MembraneFactory.majorityVoteSubsectionRepresentations(simulation.getGraph());
                variationManager.nextVariationSet();
                variationManager.logVariations();
                // create folder for this simulation
                Path timestampedFolder = Recorders.appendTimestampedFolder(observationDirectory);
                Recorders.createDirectories(timestampedFolder);
                System.out.println("writing to path " + timestampedFolder);
                // run simulation
                runSingleSimulation(simulation, timestampedFolder);
                System.out.println("finished Simulation " + (variationManager.getCurrentVariation()) + " of " + variationManager.getPossibleVariations());
                System.out.println();
            }
        } else {
            Simulation simulation = SimulationRepresentation.to(representation);
            Path timestampedFolder = Recorders.appendTimestampedFolder(observationDirectory);
            Recorders.createDirectories(timestampedFolder);
            runSingleSimulation(simulation, timestampedFolder);
        }
        return null;
    }

    private void runSingleSimulation(Simulation simulation, Path timestampedFolder) {
        System.out.println("running simulation");
        // setup manager
        SimulationManager manager = new SimulationManager(simulation);
        manager.setSimulationTerminationToTime(terminationTime);
        manager.setUpdateEmissionToTimePassed(terminationTime.divide(observations));

        // setup termination latch
        CountDownLatch terminationLatch = new CountDownLatch(1);
        manager.setTerminationLatch(terminationLatch);

        // setup logger
        NestedUpdateRecorder trajectoryObserver = new NestedUpdateRecorder(simulation, MILLI(SECOND), NANO_MOLE_PER_LITRE);
        manager.addGraphUpdateListener(trajectoryObserver);

        // add progress bar
        ProgressBarHandler progressBarHandler = new ProgressBarHandler(manager.getSimulationStatus());

        // start simulation
        Thread thread = new Thread(manager);
        thread.setDaemon(true);
        thread.start();

        try {
            terminationLatch.await();
            thread.join();
            progressBarHandler.tearDown();
            TrajectoryDataset trajectoryDataset = TrajectoryDataset.of(trajectoryObserver.getTrajectories());
            File trajectoryFile = timestampedFolder.resolve("trajectory.json").toFile();
            trajectoryDataset.write(trajectoryFile);
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }

}
