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
    private VariationManager variationManager;

    @Parameters(index = "0",
            description = "The json file with the simulation.")
    Path simulationSetupPath;

    @Option(names = {"-t", "--termination-time"},
            description = "The termination time of the simulation\n(e.g.: 10s, 0.5min, 1.5h; default: : ${DEFAULT-VALUE})",
            converter = TimeQuantityConverter.class,
            order = 0)
    Quantity<Time> terminationTime = Quantities.getQuantity(1, SECOND);

    @Option(names = {"-d", "-target-directory"},
            description = "The target folder, where observation files are created\n(default: [current working directory])",
            order = 1)
    Path targetDirectory = Paths.get("");

    @Option(names = {"-oi", "--observation-number"},
            description = {"The number of observations during simulation",
                    "default: ${DEFAULT-VALUE}"},
            order = 3)
    double observations = 100.0;

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
    @Option(names = {"-f", "--force-rerun"},
            description = {"Do not check existing folders for already processed variation sets."},
            order = 6)

    private boolean forceRerun = false;
    @Option(names = {"-i", "--ignore-variations"},
            description = {"Perform only one run with the base values in the setup file."},
            order = 7)

    private boolean ignoreVariations = false;
    @Option(names = {"-p", "--hide-progress"},
            description = {"Perform only one run with the base values in the setup file."},
            order = 8)
    boolean hideProgress = false;

    public static void main(String[] args) {
        CommandLine.call(new SimulationRunner(), args);
    }

    @Override
    public Void call() {

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
        Recorders.createDirectories(targetDirectory);

        // variation simulation
        if (ignoreVariations) {
            Simulation simulation = SimulationRepresentation.to(representation);
            Path timestampedFolder = Recorders.appendTimestampedFolder(targetDirectory);
            Recorders.createDirectories(timestampedFolder);
            runSingleSimulation(simulation, timestampedFolder);
            return null;
        }
        // create simulation (to cache entities etc)
        Simulation simulation = SimulationRepresentation.to(representation);
        variationManager = new VariationManager();
        // TODO add "big" progressbar for global simulation progress
        // for each variation set
        while (variationManager.hasVariationsLeft()) {
            System.out.println("applying variation for simulation " + (variationManager.getCurrentVariationIndex() + 1) + " of " + variationManager.getPossibleVariations());
            // create simulation
            simulation = SimulationRepresentation.to(representation);
            // set cutoff
            simulation.getScheduler().setRecalculationCutoff(0.05);
            // fix subsections
            MembraneFactory.majorityVoteSubsectionRepresentations(simulation.getGraph());
            // get next variation set
            variationManager.nextVariationSet();
            if (!forceRerun) {
                // check if the set was already processed
                variationManager.determineProcessedVariations(targetDirectory);
                String processed = variationManager.wasAlreadyProcessedIn();
                if (!processed.isEmpty()) {
                    System.out.println("set was already processed in " + processed);
                    System.out.println();
                    continue;
                }
            }
            // console out variations that are applied
            variationManager.consoleLogVariations();
            // create time stamped folder for this simulation
            Path timestampedFolder = Recorders.appendTimestampedFolder(targetDirectory);
            Recorders.createDirectories(timestampedFolder);
            System.out.println("writing to path " + timestampedFolder);
            // create variation log
            variationManager.generateJsonLog(timestampedFolder);
            System.out.println("wrote variations.log");
            // run simulation
            runSingleSimulation(simulation, timestampedFolder);
            System.out.println("finished Simulation " + (variationManager.getCurrentVariationIndex()) + " of " + variationManager.getPossibleVariations());
            System.out.println();
        }
        return null;
    }

    private void runSingleSimulation(Simulation simulation, Path timestampedFolder) {
        System.out.println("running simulation");
        // setup manager
        SimulationManager manager = new SimulationManager(simulation);
        manager.setSimulationTerminationToTime(terminationTime);
        manager.setUpdateEmissionToTimePassed(terminationTime.divide(observations));
        manager.setWriteAliveFile(true);
        manager.setTargetPath(timestampedFolder);

        // setup termination latch
        CountDownLatch terminationLatch = new CountDownLatch(1);
        manager.setTerminationLatch(terminationLatch);

        // setup logger
        NestedUpdateRecorder trajectoryObserver = new NestedUpdateRecorder(simulation, MILLI(SECOND), NANO_MOLE_PER_LITRE);
        manager.addGraphUpdateListener(trajectoryObserver);

        // add progress bar
        ProgressBarHandler progressBarHandler = null;
        if (!hideProgress) {
            progressBarHandler = new ProgressBarHandler(manager.getSimulationStatus());
        }

        // start simulation
        Thread thread = new Thread(manager);
        thread.setDaemon(true);
        thread.start();

        try {
            terminationLatch.await();
            thread.join();
            if (progressBarHandler != null) {
                progressBarHandler.tearDown();
            }
            finishAliveFile(timestampedFolder);
            TrajectoryDataset trajectoryDataset = TrajectoryDataset.of(trajectoryObserver.getTrajectories());
            if (Files.exists(timestampedFolder)) {
                File trajectoryFile = timestampedFolder.resolve("trajectory.json").toFile();
                trajectoryDataset.write(trajectoryFile);
            } else {
                // try to write to backup
                Path path = Paths.get(System.getProperty("java.io.tmpdir")).resolve("singa_backup_results");
                Path tempFolder = Recorders.appendTimestampedFolder(path);
                Recorders.createDirectories(tempFolder);
                System.out.println("unable to write file, trying to backup results in " + tempFolder);
                variationManager.generateJsonLog(tempFolder);
                File trajectoryFile = tempFolder.resolve("trajectory.json").toFile();
                trajectoryDataset.write(trajectoryFile);
            }
        } catch (InterruptedException | IOException e) {
            logger.error("unable to read process simulation for {}", timestampedFolder, e);
        }
    }

    private void finishAliveFile(Path timestampedFolder) {
        Path aliveFile = timestampedFolder.resolve("alive");
        try {
            Files.write(aliveFile, "done".getBytes());
        } catch (IOException e) {
            logger.error("unable to read alive file file {}", aliveFile, e);
        }
    }

}
