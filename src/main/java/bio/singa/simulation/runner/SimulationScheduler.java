package bio.singa.simulation.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import tech.units.indriya.quantity.Quantities;

import javax.measure.Quantity;
import javax.measure.quantity.Time;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static tech.units.indriya.unit.Units.SECOND;

/**
 * @author cl
 */
@CommandLine.Command(description = "Schedule singa simulations from multiple json files.",
        name = "singa-schedule",
        version = "v0.0.1",
        mixinStandardHelpOptions = true,
        sortOptions = false)
public class SimulationScheduler implements Callable<Void> {

    private static final Logger logger = LoggerFactory.getLogger(SimulationRunner.class);

    @CommandLine.Parameters(index = "0",
            description = "Files to process.")
    private Path setupFilePath;

    @CommandLine.Option(names = {"-t", "--termination-time"},
            description = "The termination time of the simulation\n(e.g.: 10s, 0.5min, 1.5h; default: : ${DEFAULT-VALUE})",
            converter = TimeQuantityConverter.class,
            order = 0)
    private Quantity<Time> terminationTime = Quantities.getQuantity(10, SECOND);

    @CommandLine.Option(names = {"-d", "-target-directory"},
            description = "The target folder, where observation files are created\n(default: [current working directory])",
            order = 1)
    private Path targetDirectory = Paths.get("");

    @CommandLine.Option(names = {"-oi", "--observation-number"},
            description = {"The number of observations during simulation",
                    "default: ${DEFAULT-VALUE}"},
            order = 3)
    private double observations = 100.0;
    private Path rootDirectory;

    public static void main(String[] args) {
        CommandLine.call(new SimulationScheduler(), args);
    }

    @Override
    public Void call() {

        List<String> filesToProcess;
        try {
            filesToProcess = Files.readAllLines(setupFilePath);
        } catch (IOException e) {
            logger.error("unable to read setup file {}", setupFilePath, e);
            return null;
        }
        System.out.println(filesToProcess);
        for (String setupPath : filesToProcess) {
            SimulationRunner simulationRunner = new SimulationRunner();
            // determine simulation to process
            Path simulationSetupPath = Paths.get(setupPath);
            simulationRunner.simulationSetupPath = setupFilePath.getParent().resolve(simulationSetupPath);
            // determine output directory (depending on setup file name)
            String outputDirectory = simulationSetupPath.getFileName().toString().replaceFirst("[.][^.]+$", "");
            rootDirectory = this.targetDirectory.resolve(outputDirectory);
            simulationRunner.targetDirectory = rootDirectory;
            // set time and observations
            simulationRunner.terminationTime = terminationTime;
            simulationRunner.observations = observations;
            simulationRunner.hideProgress = true;

            checkForDeadSimulations();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<Void> future = pool.submit(simulationRunner);

            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }

        }
        System.exit(0);
        return null;
    }

    private void checkForDeadSimulations() {
        // traverse all observation directories
        if (!Files.exists(rootDirectory)) {
            return;
        }
        List<String> deathList = new ArrayList<>();
        try (DirectoryStream<Path> observationDirectoryStream = Files.newDirectoryStream(rootDirectory)) {
            for (Path observationDirectoryPath : observationDirectoryStream) {
                if (Files.isDirectory(observationDirectoryPath)) {
                    String timeStamp = observationDirectoryPath.getFileName().toString();
                    // get alive file
                    Path aliveFile = observationDirectoryPath.resolve("alive");
                    if (!Files.exists(aliveFile)) {
                        deathList.add(timeStamp);
                        continue;
                    }
                    String aliveTime = String.join("", Files.readAllLines(aliveFile));
                    if (!aliveTime.equals("done")) {
                        long lastLifeSign = Long.parseLong(aliveTime);
                        long currentTime = System.currentTimeMillis();
                        // 5 minutes
                        long deathThreshold = 5 * 60 * 1000;
                        if (currentTime - lastLifeSign > deathThreshold) {
                            deathList.add(timeStamp);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Unable to retrieve simulation paths from " + targetDirectory + ".", e);
        }
        for (String deadSimulation : deathList) {
            System.out.println("Simulation "+ deadSimulation +" seems to be dead, removing it. ");
            Path deadFolder = rootDirectory.resolve(deadSimulation);
            deadFolder.resolve("variations.json").toFile().delete();
            deadFolder.resolve("alive").toFile().delete();
            deadFolder.toFile().delete();
        }
    }

}
