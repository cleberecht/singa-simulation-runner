package bio.singa.simulation.runner;

import bio.singa.features.quantities.MolarConcentration;
import bio.singa.simulation.features.variation.VariationSet;
import bio.singa.simulation.model.simulation.Simulation;
import bio.singa.simulation.model.simulation.SimulationManager;
import bio.singa.simulation.trajectories.Recorders;
import bio.singa.simulation.trajectories.nested.NestedUpdateRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import singa.bio.exchange.model.Converter;
import singa.bio.exchange.model.SimulationRepresentation;
import singa.bio.exchange.model.trajectories.TrajectoryDataset;
import singa.bio.exchange.model.variation.Observation;
import singa.bio.exchange.model.variation.ObservationRecorder;
import singa.bio.exchange.model.variation.Observations;
import singa.bio.exchange.model.variation.VariationGenerator;
import tec.units.indriya.quantity.Quantities;

import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.quantity.Time;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static bio.singa.features.units.UnitProvider.NANO_MOLE_PER_LITRE;
import static picocli.CommandLine.*;
import static tec.units.indriya.unit.MetricPrefix.MILLI;
import static tec.units.indriya.unit.Units.MINUTE;
import static tec.units.indriya.unit.Units.SECOND;

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
    private Quantity<Time> terminationTime = Quantities.getQuantity(1, MINUTE);

    @Option(names = {"-d", "-target-directory"},
            description = "The target folder, where observation files are created\n(default: [current working directory])",
            order = 1)
    private Path targetDirectory = Paths.get("");

    @Option(names = {"-of", "-observation-format"},
            completionCandidates = ObservationFormat.class,
            description = {"The observation format",
                    "options: ${COMPLETION-CANDIDATES}; default: csv)"},
            order = 2)
    private String format = "csv";

    @Option(names = {"-oi", "--observation-number"},
            description = {"The number of observations during simulation",
                    "default: 100"},
            order = 3)
    private double observations = 100.0;

    @Option(names = {"-oc", "--observed-concentration"},
            description = {"The unit in which concentrations are logged",
                    "e.g.: mol/l; default: ${DEFAULT-VALUE}"},
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
    private boolean variationRun = false;

    @Option(names = {"-vc", "--variations-configuration"},
            description = {"The json file with the observations that should be made when a variation run finishes.",
                    "This file is required if you want to perform a variation run."},
            order = 7)
    private Path observationConfigurationPath;

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
        Path observationDirectory = targetDirectory.resolve("observations");
        Recorders.createDirectories(observationDirectory);

        // variation simulation
        if (variationRun) {
            // create simulation (to cache entities etc)
            Simulation simulation = SimulationRepresentation.to(representation);
            Observations observations = prepareObservations();
            // prepare variations
            // TODO add "big" progressbar for global simulation progress
            VariationSet variationSet = VariationGenerator.generateVariationSet(representation);
            if (variationSet.getVariations().isEmpty()) {
                logger.error("no alternate values have been annotated in the simulation setup file {}", simulationSetupPath);
                return null;
            }
            // create observation recorder
            ObservationRecorder recorder = new ObservationRecorder(observations, observedConcentrationUnit, variationSet);
            logObservations(recorder);
            System.out.println();
            // generate parameters for simulations
            List<List<?>> parameterVariations = variationSet.generateAllCombinations();
            // for each variation set
            int currentSetIdentifier = 1;
            for (List<?> currentVariations : parameterVariations) {
                System.out.println("applying variation for simulation " + currentSetIdentifier + " of " + parameterVariations.size());
                logVariations(currentVariations);
                System.out.println();
                // remember setup parameters
                recorder.recordVariations(currentVariations);
                // create simulation
                simulation = SimulationRepresentation.to(representation);
                // apply variation parameters
                VariationSet.applyParameters(simulation, currentVariations);
                // create folder for this simulation
                Path timestampedFolder = Recorders.appendTimestampedFolder(observationDirectory);
                Recorders.createDirectories(timestampedFolder);
                // write variations
                VariationSet.writeVariationLog(timestampedFolder, currentVariations);
                // run simulation
                runSingleSimulation(simulation, timestampedFolder);
                // remember resulting values
                recorder.recordObservations();
                System.out.println("finished Simulation " + currentSetIdentifier + " of " + parameterVariations.size());
                System.out.println();
                currentSetIdentifier++;
            }
            recorder.writeVariationResults(observationDirectory);
        } else {
            Simulation simulation = SimulationRepresentation.to(representation);
            Path timestampedFolder = Recorders.appendTimestampedFolder(observationDirectory);
            runSingleSimulation(simulation, timestampedFolder);
        }
        return null;
    }

    private void logVariations(List<?> currentVariations) {
        for (Object currentVariation : currentVariations) {
            System.out.println("  " + currentVariation);
        }
    }

    private void logObservations(ObservationRecorder recorder) {
        System.out.println("observations: ");
        for (Observation observation : recorder.getObservations().getObservations()) {
            System.out.println("  " + observation);
        }
    }

    private Observations prepareObservations() {
        // get observation file
        String observationDocument;
        try {
            observationDocument = String.join("", Files.readAllLines(observationConfigurationPath));
        } catch (IOException e) {
            logger.error("unable to read observation file {}", simulationSetupPath, e);
            throw new UncheckedIOException(e);
        }

        // convert json to observations
        Observations observations;
        try {
            observations = Observations.getObservationsFrom(observationDocument);
        } catch (IOException e) {
            logger.error("encountered invalid or incomplete observation configuration file {}", simulationSetupPath, e);
            throw new UncheckedIOException(e);
        }

        return observations;
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
            Files.write(timestampedFolder.resolve("trajectory.json"), trajectoryDataset.toJson().getBytes());
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }

}
