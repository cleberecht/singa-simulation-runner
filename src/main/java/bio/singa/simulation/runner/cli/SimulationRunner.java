package bio.singa.simulation.runner.cli;

import bio.singa.exchange.Converter;
import bio.singa.exchange.ProcessingTicket;
import bio.singa.exchange.SimulationRepresentation;
import bio.singa.exchange.trajectories.TrajectoryDataset;
import bio.singa.simulation.model.simulation.Simulation;
import bio.singa.simulation.model.simulation.SimulationManager;
import bio.singa.simulation.runner.managers.ProgressBarManager;
import bio.singa.simulation.runner.managers.TicketManager;
import bio.singa.simulation.trajectories.Recorders;
import bio.singa.simulation.trajectories.nested.NestedUpdateRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import tech.units.indriya.quantity.Quantities;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static bio.singa.features.units.UnitProvider.NANO_MOLE_PER_LITRE;
import static picocli.CommandLine.*;
import static tech.units.indriya.unit.MetricPrefix.MICRO;
import static tech.units.indriya.unit.MetricPrefix.MILLI;
import static tech.units.indriya.unit.Units.SECOND;

/**
 * @author cl
 */
@Command(description = "Execute singa simulations from tickets.",
        name = "simulation-runner",
        version = "v0.0.2",
        mixinStandardHelpOptions = true)
public class SimulationRunner implements Callable<Void> {

    private static final Logger logger = LoggerFactory.getLogger(SimulationRunner.class);

    @Parameters(index = "0",
            description = "The folder, where ticket are pulled from.")
    private Path ticketDirectory = Paths.get("tickets");

    @Option(names = {"-t", "-target-directory"},
            description = "The target folder, where observation files are created\n(default: [current working directory])",
            order = 1)
    private Path targetDirectory = Paths.get("");

    @Option(names = {"-p", "--show-progress"},
            description = {"Show progress bar instead of log."},
            order = 2)
    private boolean showProgress = false;

    public static void main(String[] args) {
        CommandLine.call(new SimulationRunner(), args);
    }

    @Override
    public Void call() {

        System.out.println();
        System.out.println("Preparing simulation");
        System.out.println();

        // generate observation directory
        Recorders.createDirectories(targetDirectory);

        // initialize ticket manager
        TicketManager ticketManager = new TicketManager(ticketDirectory);

        while (ticketManager.ticketsAvailable()) {
            // pull ticket
            Optional<ProcessingTicket> optionalTicket = ticketManager.pullTicket();
            if (!optionalTicket.isPresent()) {
                continue;
            }
            ProcessingTicket ticket = optionalTicket.get();

            // initialize simulation
            Path simulationSetupPath = ticketDirectory.getParent().resolve(ticket.getSimulation());
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
            // create simulation
            Simulation simulation = SimulationRepresentation.to(representation);
            // set cutoff
            simulation.getScheduler().getErrorManager().setLocalNumericalTolerance(0.05);
            simulation.setMaximalTimeStep(Quantities.getQuantity(500, MICRO(SECOND)));

            // get variations from ticket
            System.out.println("applying variation for ticket " + ticket.getIdentifier());
            ticketManager.redeemTicket(ticket);
            // create time stamped folder for this simulation
            Path timestampedFolder = targetDirectory.resolve(ticket.getSimulation().replaceFirst("[.][^.]+$", "")).resolve(ticket.getIdentifier());
            Recorders.createDirectories(timestampedFolder);
            System.out.println("writing to path " + timestampedFolder);
            // create variation log
            try {
                ticket.writeFeatureSet(timestampedFolder.resolve("variations.json"));
            } catch (IOException e) {
                logger.error("unable to write variations to file {}", timestampedFolder, e);
            }
            System.out.println("wrote variations.log");
            // run simulation
            runSingleSimulation(simulation, ticket, timestampedFolder);
            ticketManager.closeTicket(ticket);
            System.out.println("finished ticket " + ticket.getIdentifier());
        }
        return null;
    }

    private void runSingleSimulation(Simulation simulation, ProcessingTicket ticket, Path timestampedFolder) {
        System.out.println("running simulation");
        // setup manager
        SimulationManager manager = new SimulationManager(simulation);
        manager.setSimulationTerminationToTime(ticket.getTotalTime());
        manager.setUpdateEmissionToTimePassed(ticket.getObservationTime());
        manager.setWriteAliveFile(true);
        manager.setTargetPath(timestampedFolder);

        // setup termination latch
        CountDownLatch terminationLatch = new CountDownLatch(1);
        manager.setTerminationLatch(terminationLatch);

        // setup logger
        NestedUpdateRecorder trajectoryObserver = new NestedUpdateRecorder(simulation, MILLI(SECOND), NANO_MOLE_PER_LITRE);
        manager.addGraphUpdateListener(trajectoryObserver);

        // add progress bar
        ProgressBarManager progressBarHandler = null;
        if (showProgress) {
            progressBarHandler = new ProgressBarManager(manager.getSimulationStatus());
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
                Path tempFolder = path.resolve(ticket.getSimulation().replaceFirst("[.][^.]+$", "")).resolve(ticket.getIdentifier());;
                Recorders.createDirectories(tempFolder);
                System.out.println("unable to write file, trying to backup results in " + tempFolder);
                ticket.writeFeatureSet(timestampedFolder.resolve("variations.json"));
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
